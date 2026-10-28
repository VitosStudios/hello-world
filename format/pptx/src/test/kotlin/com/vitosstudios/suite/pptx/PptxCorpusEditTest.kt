package com.vitosstudios.suite.pptx

import com.vitosstudios.suite.testing.Corpus
import com.vitosstudios.suite.testing.PackageCompare
import com.vitosstudios.suite.testing.SecondReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The same set of edits applied to every corpus deck: the result must re-open here and in
 * LibreOffice, and undoing everything must give back the original bytes.
 */
class PptxCorpusEditTest {
    private fun applyEdits(pres: PptxPresentation) {
        for (i in 0 until pres.slideCount) {
            val shape = pres.slideView(i).slideShapes.firstOrNull { s -> s.hasEditableText && s.text!!.paragraphs.any { it.text.isNotEmpty() } } ?: continue
            val p = shape.text!!.paragraphs.indexOfFirst { it.text.isNotEmpty() }
            pres.replaceText(i, shape.id, p, 0, 0, "[edited] ")
            pres.applyRunFormat(i, shape.id, p, 0, 8, RunFormatPatch(bold = true, rgb = 0xC00000))
        }
        val count = pres.slideCount
        pres.duplicateSlide(0)
        pres.insertSlide(pres.slideCount, pres.layouts().first().partName)
        pres.moveSlide(pres.slideCount - 1, 0)
        pres.deleteSlide(pres.slideCount - 1)
        pres.setNotesText(0, "Corpus notes")
        pres.insertShape(0, ShapeType.ELLIPSE, EmuRect(0, 0, 914400, 914400))
        pres.insertImage(0, TestImages.PNG_1X1, "png")
        assertEquals(count + 1, pres.slideCount)
    }

    @Test
    fun editsOnEveryDeckReopen() {
        val out = Corpus.tempDir("pptx-corpus-edit")
        for (f in Corpus.files("pptx")) {
            val target = File(out, f.name)
            PptxPresentation.open(f).use { pres ->
                applyEdits(pres)
                pres.save(target)
            }
            PptxPresentation.open(target).use { pres ->
                val views = pres.slideViews()
                assertTrue(views.isNotEmpty())
                assertEquals("Corpus notes", pres.notesText(0))
            }
            if (SecondReader.isAvailable) SecondReader.convert(target, "pdf")
        }
    }

    @Test
    fun undoingEverythingRestoresEveryDeck() {
        val out = Corpus.tempDir("pptx-corpus-undo")
        for (f in Corpus.files("pptx")) {
            val target = File(out, f.name)
            PptxPresentation.open(f).use { pres ->
                applyEdits(pres)
                while (pres.pkg.undo.canUndo) pres.pkg.undo.undo()
                pres.save(target)
            }
            PackageCompare.assertIdentical(f, target)
        }
    }
}
