package com.vitosstudios.suite.feature.docs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vitosstudios.suite.AppContainer
import com.vitosstudios.suite.SuiteApplication
import com.vitosstudios.suite.docx.Alignment
import com.vitosstudios.suite.docx.Block
import com.vitosstudios.suite.docx.CellPosition
import com.vitosstudios.suite.docx.DocxDocument
import com.vitosstudios.suite.docx.DocxImage
import com.vitosstudios.suite.docx.FindMatch
import com.vitosstudios.suite.docx.HeaderFooter
import com.vitosstudios.suite.docx.PageSize
import com.vitosstudios.suite.docx.Paragraph
import com.vitosstudios.suite.docx.RunPatch
import com.vitosstudios.suite.docx.Section
import com.vitosstudios.suite.docx.WordCount
import com.vitosstudios.suite.feature.common.OfficeEditorViewModel
import java.io.File
import java.util.WeakHashMap

/** State and actions of the DOCX editor. All model access happens on the main thread. */
class DocsViewModel(container: AppContainer, handle: SavedStateHandle) : OfficeEditorViewModel<DocxDocument>(container, handle) {
    override fun openDocument(file: File): DocxDocument = DocxDocument.open(file)
    override fun verifyWritten(file: File) = DocxDocument.verifyReadable(file)

    /** Paragraph being edited (body, table cell, header or footer). */
    var focused by mutableStateOf<Paragraph?>(null)
        private set

    /** Caret/selection inside [focused]. */
    var selection by mutableStateOf(TextRange.Zero)

    /** Caret requested for the next focused paragraph (after split, merge or tap). */
    var pendingCaret by mutableIntStateOf(0)
        private set

    var selectedImage by mutableStateOf<Pair<Paragraph, DocxImage>?>(null)
        private set

    /** Text scale of the reflowed view. */
    var zoom by mutableFloatStateOf(1f)
        private set

    // Find and replace
    var findQuery by mutableStateOf("")
        private set
    var replaceText by mutableStateOf("")
    var matchCase by mutableStateOf(false)
    var wholeWord by mutableStateOf(false)
    val matches = mutableStateListOf<FindMatch>()
    var currentMatch by mutableIntStateOf(-1)
        private set

    private val keys = WeakHashMap<Any, Long>()
    private var nextKey = 1L

    /** Stable LazyColumn key for a block element. */
    fun keyOf(element: Any): Long = keys.getOrPut(element) { nextKey++ }

    fun blocks(): List<Block> = document?.blocks().orEmpty()

    fun listLabels(): Map<Paragraph, String> = document?.listLabels().orEmpty()

    // ------------------------------------------------------------------------------------
    // Focus and text editing

    fun focus(p: Paragraph?, caret: Int = 0) {
        focused = p
        selectedImage = null
        pendingCaret = caret
        selection = TextRange(caret)
    }

    fun selectImage(p: Paragraph, image: DocxImage) {
        focused = null
        selectedImage = p to image
    }

    fun clearSelection() {
        focused = null
        selectedImage = null
    }

    /**
     * Applies a change made in the paragraph's text field. A newline splits the paragraph (the
     * caret moves to the new one); other changes become a minimal replace on the model.
     */
    fun onTextChanged(p: Paragraph, old: String, new: String, newSelection: TextRange) {
        val (start, oldEnd, inserted) = diff(old, new)
        val nl = inserted.indexOf('\n')
        if (nl < 0) {
            edit("Typing", coalesceKey = p) { p.replaceText(start, oldEnd, inserted) }
            selection = newSelection
            return
        }
        // Enter (or pasted lines): split once per line.
        edit("New paragraph") { doc ->
            p.replaceText(start, oldEnd, inserted.substring(0, nl))
            var current = p
            var offset = start + nl
            val lines = inserted.substring(nl + 1).split('\n')
            for (line in lines) {
                current = doc.splitParagraph(current, offset)
                if (line.isNotEmpty()) current.insertText(0, line)
                offset = line.length
            }
            focus(current, offset)
        }
    }

    /** Backspace at the start of a paragraph joins it with the previous one. */
    fun mergeBackward(p: Paragraph) {
        val doc = document ?: return
        var result: Pair<Paragraph, Int>? = null
        edit("Join paragraphs") { result = doc.mergeWithPrevious(p) }
        result?.let { (prev, caret) -> focus(prev, caret) }
    }

    /** Range the formatting commands act on: the selection, or the word at the caret. */
    private fun targetRange(p: Paragraph): IntRange? {
        val sel = selection
        if (!sel.collapsed) return minOf(sel.start, sel.end) until maxOf(sel.start, sel.end)
        val t = p.text
        if (t.isEmpty()) return null
        var a = sel.start.coerceIn(0, t.length)
        var b = a
        while (a > 0 && t[a - 1].isLetterOrDigit()) a--
        while (b < t.length && t[b].isLetterOrDigit()) b++
        return if (a < b) a until b else null
    }

    fun applyRunPatch(patch: RunPatch, label: String = "Format") {
        val p = focused ?: return
        if (!p.isTextEditable) return notify("This paragraph is read-only")
        val r = targetRange(p)
        edit(label) {
            if (r == null) p.applyMarkFormat(patch) else p.applyRunFormat(r.first, r.last + 1, patch)
        }
    }

    /** Current character format at the caret, for toolbar toggle states. */
    fun caretFormat() = focused?.let { p ->
        val r = targetRange(p)
        p.formatAt(r?.let { it.first + 1 } ?: selection.start)
    }

    fun toggleBold() { val f = caretFormat() ?: return; applyRunPatch(RunPatch(bold = !f.bold), "Bold") }
    fun toggleItalic() { val f = caretFormat() ?: return; applyRunPatch(RunPatch(italic = !f.italic), "Italic") }
    fun toggleUnderline() { val f = caretFormat() ?: return; applyRunPatch(RunPatch(underline = !f.underline), "Underline") }
    fun toggleStrike() { val f = caretFormat() ?: return; applyRunPatch(RunPatch(strike = !f.strike), "Strikethrough") }

    // ------------------------------------------------------------------------------------
    // Paragraph formatting

    private fun withFocused(label: String, block: (DocxDocument, Paragraph) -> Unit) {
        val p = focused ?: return notify("Tap a paragraph first")
        edit(label) { block(it, p) }
    }

    fun setHeading(level: Int) = withFocused("Style") { _, p -> p.setHeadingLevel(level) }
    fun setStyle(styleId: String?) = withFocused("Style") { _, p -> p.setStyle(styleId) }
    fun setAlignment(a: Alignment) = withFocused("Alignment") { _, p -> p.setAlignment(a) }
    fun changeIndent(increase: Boolean) = withFocused("Indent") { d, p ->
        if (p.format.numId != null) d.changeListLevel(p, if (increase) 1 else -1) else p.changeIndent(increase)
    }
    fun setLineSpacing(multiple: Float) = withFocused("Line spacing") { _, p -> p.setSpacing(lineMultiple = multiple) }
    fun setSpacing(before: Float?, after: Float?) = withFocused("Spacing") { _, p -> p.setSpacing(before = before, after = after) }
    fun toggleBullets() = withFocused("Bulleted list") { d, p -> d.toggleBulletList(p) }
    fun toggleNumbering() = withFocused("Numbered list") { d, p -> d.toggleNumberedList(p) }

    fun insertPageBreak() = withFocused("Page break") { d, p ->
        val next = d.insertPageBreak(p, selection.end.coerceIn(0, p.text.length))
        focus(next, 1)
    }

    fun insertParagraphBelow() {
        val doc = document ?: return
        val p = focused
        var created: Paragraph? = null
        edit("New paragraph") {
            created = doc.insertParagraphAfter(p?.let { Block.Para(it) } ?: doc.blocks().lastOrNull())
        }
        created?.let { focus(it, 0) }
    }

    fun deleteFocusedParagraph() = withFocused("Delete paragraph") { d, p ->
        d.deleteBlock(Block.Para(p))
        focused = null
    }

    fun moveFocused(up: Boolean) = withFocused("Move paragraph") { d, p ->
        if (!d.moveBlock(Block.Para(p), up)) notify("This paragraph cannot move further")
    }

    // ------------------------------------------------------------------------------------
    // Insertions

    fun insertTable(rows: Int, cols: Int) = withFocused("Insert table") { d, p ->
        val t = d.insertTable(p, rows, cols)
        focus(t.rows.first().cells.first().paragraphs.first(), 0)
    }

    fun insertImage(bytes: ByteArray, extension: String, widthPx: Int, heightPx: Int) {
        val doc = document ?: return
        val p = focused?.takeIf { it.isTextEditable } ?: doc.bodyParagraphs().lastOrNull { it.isTextEditable }
            ?: return notify("There is no editable paragraph to place the image in")
        val offset = if (p == focused) selection.end.coerceIn(0, p.text.length) else p.text.length
        edit("Insert image") { doc.insertImage(p, offset, bytes, extension, widthPx, heightPx) }
    }

    fun resizeSelectedImage(scale: Float) {
        val (p, img) = selectedImage ?: return
        val doc = document ?: return
        edit("Resize image") { doc.resizeImage(img, img.widthPt * scale, img.heightPt * scale) }
        // Re-read the image so the next resize starts from the new size.
        val updated = p.atoms.mapNotNull { it.second.image }.firstOrNull { it.drawing === img.drawing }
        selectedImage = updated?.let { p to it }
    }

    fun deleteSelectedImage() {
        val (p, img) = selectedImage ?: return
        val pos = p.atoms.firstOrNull { it.second.image?.drawing === img.drawing }?.first ?: return
        edit("Delete image") { p.deleteText(pos, pos + 1) }
        selectedImage = null
    }

    fun imageBytes(p: Paragraph, img: DocxImage): ByteArray? = document?.imageBytes(p.part, img)

    fun hyperlinkAtCaret(): String? {
        val p = focused ?: return null
        val d = document ?: return null
        val h = d.hyperlinkAt(p, selection.start) ?: return null
        return d.hyperlinkTarget(p.part, h)
    }

    fun setLink(url: String) = withFocused("Link") { d, p ->
        val normalized = normalizeUrl(url)
        val existing = d.hyperlinkAt(p, selection.start)
        if (existing != null) d.setHyperlinkUrl(p, existing, normalized)
        else {
            val r = targetRange(p) ?: throw IllegalStateException("Select the text for the link first")
            d.insertHyperlink(p, r.first, r.last + 1, normalized)
        }
    }

    fun removeLink() = withFocused("Remove link") { d, p ->
        d.hyperlinkAt(p, selection.start)?.let(d::removeHyperlink)
    }

    // ------------------------------------------------------------------------------------
    // Tables

    fun cellOfFocused(): CellPosition? = focused?.let { document?.cellOf(it) }

    fun tableAction(label: String, action: (CellPosition) -> Unit) {
        val pos = cellOfFocused() ?: return
        edit(label) { action(pos) }
        // The focused cell may be gone; keep focus only if still attached.
        if (focused?.element?.document == null) focused = null
    }

    fun deleteTable() {
        val pos = cellOfFocused() ?: return
        edit("Delete table") { pos.table.doc.deleteTable(pos.table) }
        focused = null
    }

    // ------------------------------------------------------------------------------------
    // Page setup, headers and footers

    fun currentSection(): Section? = document?.let { d -> focused?.let { d.sectionOf(it) } ?: d.sections().lastOrNull() }

    fun setPageSize(size: PageSize) {
        val d = document ?: return
        edit("Page size") { d.sections().forEach { s -> d.setPageSize(s, size.widthPt, size.heightPt) } }
    }

    fun setOrientation(landscape: Boolean) {
        val d = document ?: return
        edit("Orientation") { d.sections().forEach { s -> d.setOrientation(s, landscape) } }
    }

    fun setMargins(top: Float, right: Float, bottom: Float, left: Float) {
        val d = document ?: return
        edit("Margins") {
            if (d.sections().isEmpty()) d.ensureSection()
            d.sections().forEach { s -> d.setMargins(s, top, right, bottom, left) }
        }
    }

    fun headerFooter(kind: HeaderFooter.Kind, create: Boolean): HeaderFooter? {
        val d = document ?: return null
        val s = currentSection() ?: if (create) d.ensureSection() else return null
        if (!create) return d.headerFooter(s, kind)
        var hf: HeaderFooter? = null
        edit(if (kind == HeaderFooter.Kind.HEADER) "Add header" else "Add footer") { hf = d.ensureHeaderFooter(s, kind) }
        return hf
    }

    // ------------------------------------------------------------------------------------
    // Find and replace

    fun find(query: String) {
        findQuery = query
        matches.clear()
        val d = document ?: return
        matches.addAll(d.find(query, matchCase, wholeWord))
        currentMatch = if (matches.isEmpty()) -1 else 0
    }

    fun refreshFind() = find(findQuery)

    fun nextMatch(forward: Boolean) {
        if (matches.isEmpty()) return
        currentMatch = (currentMatch + if (forward) 1 else -1).mod(matches.size)
    }

    fun replaceCurrent() {
        val d = document ?: return
        val m = matches.getOrNull(currentMatch) ?: return
        var ok = false
        edit("Replace") { ok = d.replace(m, replaceText) }
        if (!ok) notify("That match is in a read-only paragraph")
        refreshFind()
    }

    fun replaceAll() {
        val d = document ?: return
        var result: com.vitosstudios.suite.docx.ReplaceResult? = null
        edit("Replace all") { result = d.replaceAll(findQuery, replaceText, matchCase, wholeWord) }
        result?.let { r ->
            notify("Replaced ${r.replaced}" + if (r.skippedReadOnly > 0) " (${r.skippedReadOnly} in read-only paragraphs skipped)" else "")
        }
        refreshFind()
    }

    fun closeFind() {
        findQuery = ""
        matches.clear()
        currentMatch = -1
    }

    fun wordCount(): WordCount? = document?.wordCount()

    fun zoomBy(factor: Float) { zoom = (zoom * factor).coerceIn(0.6f, 2.5f) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SuiteApplication
                DocsViewModel(app.container, createSavedStateHandle())
            }
        }

        /** Minimal edit between two strings: (start, end in old, inserted text). */
        fun diff(old: String, new: String): Triple<Int, Int, String> {
            var start = 0
            while (start < old.length && start < new.length && old[start] == new[start]) start++
            var endOld = old.length
            var endNew = new.length
            while (endOld > start && endNew > start && old[endOld - 1] == new[endNew - 1]) { endOld--; endNew-- }
            return Triple(start, endOld, new.substring(start, endNew))
        }

        fun normalizeUrl(url: String): String {
            val u = url.trim()
            return when {
                u.isEmpty() -> u
                u.startsWith("#") -> u
                Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(u) -> u
                u.contains('@') && !u.contains('/') -> "mailto:$u"
                else -> "https://$u"
            }
        }
    }
}
