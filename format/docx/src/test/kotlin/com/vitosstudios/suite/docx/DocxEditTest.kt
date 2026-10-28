package com.vitosstudios.suite.docx

import com.vitosstudios.suite.testing.Corpus
import com.vitosstudios.suite.testing.PackageCompare
import com.vitosstudios.suite.testing.SecondReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One edit per feature. Each test saves, re-opens with this app's reader, checks that only the
 * expected parts changed, and (when LibreOffice is installed) that a second reader opens the file
 * and sees the edit.
 */
class DocxEditTest {
    private val out = Corpus.tempDir("docx-edit")
    private val features = Corpus.file("docx/pylib-features.docx")
    private val word = Corpus.file("docx/word-poi-sample.docx")
    private val lo = Corpus.file("docx/libreoffice-features.docx")

    private fun edit(src: File, name: String, block: (DocxDocument) -> Unit): File {
        val target = File(out, "$name.docx")
        DocxDocument.open(src).use { d ->
            block(d)
            d.save(target)
        }
        return target
    }

    private fun firstEditable(d: DocxDocument): Paragraph = d.bodyParagraphs().first { it.isTextEditable && it.text.isNotBlank() }

    private fun assertSecondReaderSees(file: File, vararg texts: String) {
        if (!SecondReader.isAvailable) { SecondReader.assume(); return }
        val content = SecondReader.text(file)
        for (t in texts) assertTrue(content.contains(t), "LibreOffice does not show '$t' in ${file.name}")
    }

    @Test
    fun editText() {
        for (src in listOf(features, word, lo)) {
            val f = edit(src, "text-${src.nameWithoutExtension}") { d ->
                val p = firstEditable(d)
                p.replaceText(0, 0, "Edited: ")
                p.insertText(p.text.length, " [end]")
            }
            DocxDocument.open(f).use { d ->
                val p = firstEditable(d)
                assertTrue(p.text.startsWith("Edited: "), p.text)
                assertTrue(p.text.endsWith(" [end]"))
            }
            PackageCompare.assertOnlyChanged(src, f, setOf("word/document.xml"))
            assertSecondReaderSees(f, "Edited: ", "[end]")
        }
    }

    @Test
    fun characterFormatting() {
        val f = edit(features, "format") { d ->
            val p = d.bodyParagraphs().first { it.text.startsWith("Plain") }
            p.applyRunFormat(0, 5, RunPatch(bold = true, italic = true, underline = true, strike = true, sizePt = 18f, color = "336699", highlight = "green", fontFamily = "Liberation Serif"))
        }
        DocxDocument.open(f).use { d ->
            val p = d.bodyParagraphs().first { it.text.startsWith("Plain") }
            val fmt = p.spans().first().format
            assertTrue(fmt.bold && fmt.italic && fmt.underline && fmt.strike)
            assertEquals(18f, fmt.sizePt)
            assertEquals("336699", fmt.color)
            assertEquals("green", fmt.highlight)
            assertEquals("Liberation Serif", fmt.fontFamily)
            assertEquals(5, p.spans().first().end)
            assertEquals("Plain, ", p.text.substring(0, 7))
        }
        PackageCompare.assertOnlyChanged(features, f, setOf("word/document.xml"))
        if (SecondReader.isAvailable) {
            val fodt = SecondReader.text(f)
            assertTrue(fodt.contains("fo:font-weight=\"bold\"") && fodt.contains("#336699", ignoreCase = true))
        }
    }

    @Test
    fun removeFormattingAndToggleOff() {
        val f = edit(features, "unformat") { d ->
            val p = d.bodyParagraphs().first { it.text.startsWith("Plain") }
            val boldStart = p.text.indexOf("bold")
            p.applyRunFormat(boldStart, boldStart + 4, RunPatch(bold = false))
        }
        DocxDocument.open(f).use { d ->
            val p = d.bodyParagraphs().first { it.text.startsWith("Plain") }
            assertFalse(p.formatAt(p.text.indexOf("bold") + 2).bold)
        }
    }

    @Test
    fun headingsUseDocumentStyles() {
        val f = edit(word, "heading") { d ->
            val p = firstEditable(d)
            p.setHeadingLevel(2)
        }
        DocxDocument.open(f).use { d ->
            val p = firstEditable(d)
            assertEquals(2, p.headingLevel)
            assertEquals(d.styles.headingStyleId(2), p.styleId)
        }
        PackageCompare.assertOnlyChanged(word, f, setOf("word/document.xml", "word/styles.xml"))
        assertSecondReaderSees(f)
    }

    @Test
    fun headingStyleIsAddedWhenMissing() {
        val f = edit(Corpus.file("docx/word-poi-TestDocument.docx"), "heading-added") { d ->
            firstEditable(d).setHeadingLevel(5)
        }
        DocxDocument.open(f).use { d -> assertEquals(5, firstEditable(d).headingLevel) }
        assertSecondReaderSees(f)
    }

    @Test
    fun listsBulletNumberAndNesting() {
        val f = edit(word, "lists") { d ->
            val ps = d.bodyParagraphs().filter { it.isTextEditable && it.text.isNotBlank() }.take(3)
            d.toggleBulletList(ps[0])
            d.toggleNumberedList(ps[1])
            d.toggleNumberedList(ps[2])
            d.changeListLevel(ps[2], 1)
        }
        DocxDocument.open(f).use { d ->
            val ps = d.bodyParagraphs().filter { it.isTextEditable && it.text.isNotBlank() }.take(3)
            val labels = d.listLabels()
            assertEquals("•", labels[ps[0]])
            assertEquals("1.", labels[ps[1]])
            assertEquals("a.", labels[ps[2]])
            assertTrue(d.numbering.isBulleted(ps[0]))
        }
        PackageCompare.assertOnlyChanged(word, f, setOf("word/document.xml", "word/numbering.xml", "[Content_Types].xml", "word/_rels/document.xml.rels"))
        assertSecondReaderSees(f)
    }

    @Test
    fun removeListFromStyleBasedList() {
        val f = edit(features, "unlist") { d ->
            val p = d.bodyParagraphs().first { it.text == "First bullet" }
            assertTrue(d.numbering.isBulleted(p))
            d.toggleBulletList(p)
        }
        DocxDocument.open(f).use { d ->
            val p = d.bodyParagraphs().first { it.text == "First bullet" }
            assertNull(d.listLabels()[p])
        }
    }

    @Test
    fun alignmentIndentSpacing() {
        val f = edit(word, "paragraph-format") { d ->
            val p = firstEditable(d)
            p.setAlignment(Alignment.CENTER)
            p.setIndent(left = 36f, right = 18f, firstLine = -18f)
            p.setSpacing(before = 6f, after = 12f, lineMultiple = 1.5f)
        }
        DocxDocument.open(f).use { d ->
            val fmt = firstEditable(d).format
            assertEquals(Alignment.CENTER, fmt.alignment)
            assertEquals(36f, fmt.indentLeft)
            assertEquals(18f, fmt.indentRight)
            assertEquals(-18f, fmt.indentFirstLine)
            assertEquals(6f, fmt.spaceBefore)
            assertEquals(12f, fmt.spaceAfter)
            assertEquals(1.5f, fmt.lineMultiple)
        }
        PackageCompare.assertOnlyChanged(word, f, setOf("word/document.xml"))
        if (SecondReader.isAvailable) assertTrue(SecondReader.text(f).contains("fo:text-align=\"center\""))
    }

    @Test
    fun tablesInsertAndEditStructure() {
        val f = edit(word, "table") { d ->
            val p = firstEditable(d)
            val t = d.insertTable(p, 2, 3)
            t.rows[0].cells[0].paragraphs[0].insertText(0, "Cell A1")
            t.insertRow(2)
            t.insertColumn(1)
            t.rows[2].cells[3].paragraphs[0].insertText(0, "Corner")
            t.deleteColumn(1)
            t.insertRow(0)
            t.deleteRow(0)
        }
        DocxDocument.open(f).use { d ->
            val t = d.blocks().filterIsInstance<Block.Table>().first().table
            assertEquals(3, t.rows.size)
            assertEquals(3, t.columnCount)
            assertEquals("Cell A1", t.rows[0].cells[0].paragraphs[0].text)
            assertEquals("Corner", t.rows[2].cells[2].paragraphs[0].text)
            assertEquals(3, t.columnWidthsTwips.size)
        }
        PackageCompare.assertOnlyChanged(word, f, setOf("word/document.xml"))
        assertSecondReaderSees(f, "Cell A1", "Corner")
    }

    @Test
    fun mergedTablesKeepStructure() {
        DocxDocument.open(features).use { d ->
            val t = d.blocks().filterIsInstance<Block.Table>().first().table
            assertTrue(t.hasMergedCells)
            assertFailsWith<ReadOnlyContentException> { t.insertRow(1) }
            // Cell text stays editable.
            t.rows[0].cells[0].paragraphs[0].insertText(0, "x")
        }
    }

    @Test
    fun imagesInsertAndResize() {
        val png = Corpus.file("docx/word-poi-VariousPictures.docx").let { src ->
            DocxDocument.open(src).use { d ->
                val p = d.bodyParagraphs().first { p -> p.atoms.any { it.second.kind == AtomKind.IMAGE } }
                d.imageBytes(p.part, p.atoms.first { it.second.kind == AtomKind.IMAGE }.second.image!!)!!
            }
        }
        val f = edit(word, "image") { d ->
            val p = firstEditable(d)
            val img = d.insertImage(p, 3, png, "png", 200, 100)
            d.resizeImage(img, 100f, 50f)
        }
        DocxDocument.open(f).use { d ->
            val p = firstEditable(d)
            val (pos, atom) = p.atoms.first { it.second.kind == AtomKind.IMAGE }
            assertEquals(3, pos)
            assertEquals(100f, atom.image!!.widthPt, 0.01f)
            assertEquals(50f, atom.image!!.heightPt, 0.01f)
            assertTrue(d.imageBytes(p.part, atom.image!!)!!.contentEquals(png), "Original image bytes must be kept")
        }
        PackageCompare.assertOnlyChanged(word, f, setOf("word/document.xml", "word/_rels/document.xml.rels", "[Content_Types].xml")) { it.startsWith("word/media/") }
        if (SecondReader.isAvailable) assertTrue(SecondReader.text(f).contains("draw:image"))
    }

    @Test
    fun deletingAcrossAnImageRemovesItButKeepsProtectedObjects() {
        DocxDocument.open(Corpus.file("docx/word-poi-footnotes.docx")).use { d ->
            val p = d.bodyParagraphs().first { p -> p.atoms.any { it.second.kind == AtomKind.FOOTNOTE_REFERENCE } }
            val before = p.atoms.size
            p.deleteText(0, p.text.length)
            assertEquals(before, p.atoms.size, "Footnote references are protected")
            assertEquals(InlineChars.OBJECT.toString().repeat(before), p.text)
        }
    }

    @Test
    fun headersAndFooters() {
        val f = edit(features, "header") { d ->
            val h = d.ensureHeaderFooter(d.sections().first(), HeaderFooter.Kind.HEADER)
            h.paragraphs.first().replaceText(0, h.paragraphs.first().text.length, "New header")
        }
        DocxDocument.open(f).use { d ->
            assertEquals("New header", d.headerFooter(d.sections().first(), HeaderFooter.Kind.HEADER)!!.paragraphs.first().text)
        }
        assertSecondReaderSees(f, "New header")
        // Document without footer: one is created.
        val noFooter = Corpus.files("docx", includeLarge = false).first { f ->
            DocxDocument.open(f).use { d -> d.sections().isNotEmpty() && d.headerFooter(d.sections().last(), HeaderFooter.Kind.FOOTER) == null }
        }
        val g = edit(noFooter, "footer-created") { d ->
            val s = d.sections().last()
            val ft = d.ensureHeaderFooter(s, HeaderFooter.Kind.FOOTER)
            ft.paragraphs.first().insertText(0, "Created footer")
        }
        DocxDocument.open(g).use { d ->
            assertEquals("Created footer", d.headerFooter(d.sections().last(), HeaderFooter.Kind.FOOTER)!!.paragraphs.first().text)
        }
        PackageCompare.assertOnlyChanged(noFooter, g, setOf("word/document.xml", "word/_rels/document.xml.rels", "[Content_Types].xml")) { it.startsWith("word/footer") }
        assertSecondReaderSees(g, "Created footer")
    }

    @Test
    fun hyperlinks() {
        val f = edit(word, "link") { d ->
            val p = firstEditable(d)
            d.insertHyperlink(p, 0, 4, "https://example.com/a")
        }
        DocxDocument.open(f).use { d ->
            val p = firstEditable(d)
            assertEquals("https://example.com/a", p.spans().first().link)
            val h = d.hyperlinkAt(p, 1)!!
            d.setHyperlinkUrl(p, h, "https://example.com/b")
            assertEquals("https://example.com/b", p.spans().first().link)
            d.removeHyperlink(h)
            assertNull(p.spans().first().link)
        }
        assertSecondReaderSees(f, "https://example.com/a")
    }

    @Test
    fun pageBreakAndSplitMerge() {
        val f = edit(word, "breaks") { d ->
            val p = firstEditable(d)
            val text = p.text
            val second = d.splitParagraph(p, 4)
            assertEquals(text.substring(0, 4), p.text)
            assertEquals(text.substring(4), second.text)
            val (joined, caret) = d.mergeWithPrevious(second)!!
            assertEquals(text, joined.text)
            assertEquals(4, caret)
            d.insertPageBreak(joined, 4)
        }
        DocxDocument.open(f).use { d ->
            assertTrue(d.bodyParagraphs().any { p -> p.atoms.any { it.second.kind == AtomKind.PAGE_BREAK } })
        }
        if (SecondReader.isAvailable) {
            val fodt = SecondReader.text(f)
            assertTrue(fodt.contains("fo:break-before=\"page\""), "LibreOffice must see the page break")
        }
    }

    @Test
    fun pageSetup() {
        val f = edit(word, "page-setup") { d ->
            val s = d.ensureSection()
            d.setPageSize(s, PageSize.A4.widthPt, PageSize.A4.heightPt)
            d.setMargins(s, top = 36f, right = 36f, bottom = 36f, left = 36f)
            d.setOrientation(s, landscape = true)
        }
        DocxDocument.open(f).use { d ->
            val s = d.sections().last()
            assertTrue(s.isLandscape)
            assertEquals(841.9f, s.pageWidthPt, 0.1f)
            assertEquals(36f, s.marginLeftPt)
        }
        if (SecondReader.isAvailable) assertTrue(SecondReader.text(f).contains("style:print-orientation=\"landscape\""))
    }

    @Test
    fun findReplaceAndWordCount() {
        val f = edit(features, "replace") { d ->
            val before = d.find("bullet")
            assertTrue(before.size >= 3)
            val result = d.replaceAll("bullet", "item")
            assertEquals(before.size, result.replaced)
            assertTrue(d.find("bullet").isEmpty())
            val wc = d.wordCount()
            assertTrue(wc.words > 30, "words=${wc.words}")
        }
        DocxDocument.open(f).use { d -> assertTrue(d.find("First item").isNotEmpty()) }
        assertSecondReaderSees(f, "First item")
    }

    @Test
    fun wordCountMatchesSimpleText() {
        DocxDocument.createNew().use { d ->
            d.bodyParagraphs().first().insertText(0, "One two\tthree, four-five.")
            assertEquals(4, d.wordCount().words)
        }
    }

    @Test
    fun undoRestoresIdenticalFile() {
        val target = File(out, "undo.docx")
        DocxDocument.open(word).use { d ->
            val p = firstEditable(d)
            p.insertText(0, "abc")
            p.applyRunFormat(0, 3, RunPatch(bold = true))
            d.toggleNumberedList(p)
            d.insertTable(p, 2, 2)
            d.insertHyperlink(p, 0, 2, "https://x.test")
            while (d.pkg.undo.canUndo) d.pkg.undo.undo()
            d.save(target)
        }
        PackageCompare.assertIdentical(word, target)
    }

    @Test
    fun readOnlyParagraphsRejectTextEdits() {
        DocxDocument.open(Corpus.file("docx/word-poi-delins.docx")).use { d ->
            val p = d.bodyParagraphs().first { !it.isTextEditable }
            assertTrue("tracked changes" in p.lockReasons)
            assertFailsWith<ReadOnlyContentException> { p.insertText(0, "x") }
            // Paragraph formatting is still allowed.
            p.setAlignment(Alignment.RIGHT)
        }
    }

    @Test
    fun newDocumentsFromTemplatesOpenEverywhere() {
        for (t in DocxTemplate.entries) {
            val target = File(out, "new-${t.name}.docx")
            DocxDocument.createNew(t).use { d ->
                d.bodyParagraphs().first().insertText(0, "Hello ${t.name}")
                d.save(target)
            }
            DocxDocument.open(target).use { d -> assertNotNull(d.bodyParagraphs().first { it.text.startsWith("Hello") }) }
            assertSecondReaderSees(target, "Hello ${t.name}")
        }
    }

    @Test
    fun largeDocumentStaysResponsive() {
        val big = Corpus.file("large/pylib-200-pages.docx")
        val t0 = System.nanoTime()
        DocxDocument.open(big).use { d ->
            val ps = d.bodyParagraphs()
            assertTrue(ps.size > 2000)
            ps.forEach { it.spans() }
            d.listLabels(ps)
            val opened = (System.nanoTime() - t0) / 1e6
            val t1 = System.nanoTime()
            ps[1000].insertText(0, "x")
            ps[1000].applyRunFormat(0, 1, RunPatch(bold = true))
            val edited = (System.nanoTime() - t1) / 1e6
            val t2 = System.nanoTime()
            d.save(File(out, "large.docx"))
            val saved = (System.nanoTime() - t2) / 1e6
            println("200 pages: open+layout model ${opened.toInt()} ms, edit ${edited.toInt()} ms, save ${saved.toInt()} ms")
            assertTrue(opened < 8000 && edited < 500 && saved < 8000, "too slow: open=$opened edit=$edited save=$saved")
        }
    }
}
