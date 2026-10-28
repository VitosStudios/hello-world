package com.vitosstudios.suite.pptx

import com.vitosstudios.suite.testing.Corpus
import com.vitosstudios.suite.testing.PackageCompare
import com.vitosstudios.suite.testing.SecondReader
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One edit per feature. Each test saves, re-opens with this layer, checks that only the expected
 * parts changed and that LibreOffice (the second reader) opens the file and sees the edit.
 */
class PptxEditTest {
    private val sample = Corpus.file("pptx/powerpoint-poi-SampleShow.pptx")
    private val features = Corpus.file("pptx/pylib-features.pptx")

    private fun out(name: String): File = File(Corpus.tempDir("pptx-edit"), "$name.pptx")

    private fun edit(source: File, name: String, block: (PptxPresentation) -> Unit): File {
        val target = out(name)
        PptxPresentation.open(source).use { pres ->
            block(pres)
            pres.save(target)
        }
        return target
    }

    private fun secondReaderSees(file: File, vararg texts: String) {
        if (!SecondReader.isAvailable) return SecondReader.assume()
        val content = SecondReader.text(file)
        for (t in texts) assertTrue(content.contains(t), "LibreOffice does not show '$t' in ${file.name}")
    }

    private fun entry(file: File, name: String): String? =
        ZipFile(file).use { z -> z.getEntry(name)?.let { e -> z.getInputStream(e).use { it.readBytes().decodeToString() } } }

    private fun titleId(pres: PptxPresentation, slide: Int) =
        pres.slideView(slide).slideShapes.first { it.placeholder?.type in setOf("title", "ctrTitle") }.id

    // ----------------------------------------------------------------------------------------
    // Text

    @Test
    fun editText() {
        val f = edit(sample, "text") { pres ->
            val id = titleId(pres, 0)
            val text = pres.slideView(0).shape(id)!!.text!!.paragraphs[0].text
            val start = text.indexOf("first")
            pres.replaceText(0, id, 0, start, start + 5, "edited")
        }
        PptxPresentation.open(f).use { pres ->
            assertEquals("Title of the edited slide", pres.slideView(0).shape(titleId(pres, 0))!!.text!!.plainText)
        }
        PackageCompare.assertOnlyChanged(sample, f, setOf("ppt/slides/slide1.xml"))
        secondReaderSees(f, "Title of the edited slide")
    }

    @Test
    fun boldSizeColorAndTypeface() {
        val f = edit(sample, "format") { pres ->
            val id = titleId(pres, 0)
            pres.applyRunFormat(0, id, 0, 0, 5, RunFormatPatch(bold = true, sizePt = 54.0, rgb = 0x2060A0, typeface = "Georgia"))
            pres.applyRunFormat(0, id, 0, 9, 12, RunFormatPatch(italic = true, underline = true, strike = true))
        }
        PptxPresentation.open(f).use { pres ->
            val runs = pres.slideView(0).shape(titleId(pres, 0))!!.text!!.paragraphs[0].runs
            val first = runs.first { it.text == "Title" }
            assertTrue(first.bold)
            assertEquals(54.0, first.sizePt)
            assertEquals(0xFF2060A0.toInt(), first.argb)
            assertEquals("Georgia", first.typeface)
            val the = runs.first { it.text == "the" }
            assertTrue(the.italic && the.underline && the.strike)
            assertFalse(runs.first { it.text.startsWith(" of") }.bold)
            assertEquals("Title of the first slide", runs.joinToString("") { it.text })
        }
        PackageCompare.assertOnlyChanged(sample, f, setOf("ppt/slides/slide1.xml"))
        secondReaderSees(f, "Title")
    }

    @Test
    fun bulletsAlignmentAndLevels() {
        val f = edit(features, "bullets") { pres ->
            val body = pres.slideView(1).slideShapes.first { it.placeholder?.index == 1 }
            pres.setBullet(1, body.id, 0, BulletStyle.Numbered())
            pres.setBullet(1, body.id, 1, BulletStyle.Numbered())
            pres.setParagraphAlignment(1, body.id, 0, TextAlign.RIGHT)
            pres.setParagraphLevel(1, body.id, 2, 2)
            pres.setBullet(1, body.id, 2, BulletStyle.None)
        }
        PptxPresentation.open(f).use { pres ->
            val ps = pres.slideView(1).slideShapes.first { it.placeholder?.index == 1 }.text!!.paragraphs
            assertEquals("1.", ps[0].bullet.label)
            assertEquals("2.", ps[1].bullet.label)
            assertEquals(TextAlign.RIGHT, ps[0].alignment)
            assertEquals(2, ps[2].level)
            assertEquals(BulletType.NONE, ps[2].bullet.type)
        }
        PackageCompare.assertOnlyChanged(features, f, setOf("ppt/slides/slide2.xml"))
        secondReaderSees(f, "First point", "Sub point")
    }

    @Test
    fun splitMergeAndLineBreaks() {
        val f = edit(sample, "paragraphs") { pres ->
            val id = titleId(pres, 0)
            pres.splitParagraph(0, id, 0, 5)
            pres.replaceText(0, id, 1, 0, 0, "New$LINE_BREAK")
            pres.splitParagraph(0, id, 1, 0)
            pres.mergeWithNextParagraph(0, id, 1)
        }
        PptxPresentation.open(f).use { pres ->
            val ps = pres.slideView(0).shape(titleId(pres, 0))!!.text!!.paragraphs
            assertEquals(listOf("Title", "New$LINE_BREAK of the first slide"), ps.map { it.text })
            assertEquals(RunKind.BREAK, ps[1].runs.first { it.text == LINE_BREAK.toString() }.kind)
        }
        secondReaderSees(f, "of the first slide")
    }

    @Test
    fun charactersXmlCannotHoldAreDropped() {
        val f = edit(sample, "control-chars") { pres ->
            pres.replaceText(0, titleId(pres, 0), 0, 0, 0, "A\u0001B\uFFFF\uD800C\t")
        }
        PptxPresentation.open(f).use { pres ->
            assertTrue(pres.slideView(0).shape(titleId(pres, 0))!!.text!!.plainText.startsWith("ABC\t"))
        }
        secondReaderSees(f, "ABC")
    }

    @Test
    fun shapeTextOnGeometricShapes() {
        val f = edit(features, "shape-text") { pres ->
            val rect = pres.slideView(2).slideShapes.first { it.geometry == "rect" && it.kind == ShapeKind.SHAPE }
            pres.setShapeText(2, rect.id, "Inside the rectangle\nSecond line")
        }
        PptxPresentation.open(f).use { pres ->
            assertTrue(pres.slideView(2).slideShapes.any { it.text?.plainText == "Inside the rectangle\nSecond line" })
        }
        secondReaderSees(f, "Inside the rectangle", "Second line")
    }

    // ----------------------------------------------------------------------------------------
    // Slides

    @Test
    fun insertSlideFromChosenLayout() {
        val f = edit(sample, "insert-slide") { pres ->
            val layout = pres.layouts().first { it.name == "Title and Content" }
            val index = pres.insertSlide(1, layout.partName)
            val title = titleId(pres, index)
            pres.replaceText(index, title, 0, 0, 0, "Inserted slide")
            assertEquals(layout.partName, pres.slideView(index).layoutPartName)
        }
        PptxPresentation.open(f).use { pres ->
            assertEquals(3, pres.slideCount)
            assertEquals("Inserted slide", pres.slideView(1).shape(titleId(pres, 1))!!.text!!.plainText)
            assertEquals("Title of the first slide", pres.slideView(0).shape(titleId(pres, 0))!!.text!!.plainText)
        }
        PackageCompare.assertOnlyChanged(sample, f, setOf("ppt/presentation.xml", "ppt/_rels/presentation.xml.rels", "[Content_Types].xml")) {
            it.startsWith("ppt/slides/")
        }
        secondReaderSees(f, "Inserted slide")
    }

    @Test
    fun deleteSlideRemovesItsNotes() {
        val f = edit(sample, "delete-slide") { pres -> pres.deleteSlide(0) }
        PptxPresentation.open(f).use { pres ->
            assertEquals(1, pres.slideCount)
            assertTrue(pres.notesText(0).startsWith("These are the notes of the 2nd slide\nTHIS LINE IS BOLD"), pres.notesText(0))
        }
        PackageCompare.assertOnlyChanged(
            sample, f,
            setOf("ppt/presentation.xml", "ppt/_rels/presentation.xml.rels", "[Content_Types].xml",
                "ppt/slides/slide1.xml", "ppt/slides/_rels/slide1.xml.rels",
                "ppt/notesSlides/notesSlide1.xml", "ppt/notesSlides/_rels/notesSlide1.xml.rels"),
        )
        assertNull(entry(f, "ppt/slides/slide1.xml"))
        assertNull(entry(f, "ppt/notesSlides/notesSlide1.xml"))
        PptxPresentation.open(f).use { pres -> assertFailsWith<PptxEditException> { pres.deleteSlide(0) } }
        secondReaderSees(f, "second")
    }

    @Test
    fun duplicateSlideCopiesNotes() {
        val f = edit(sample, "duplicate-slide") { pres ->
            assertEquals(1, pres.duplicateSlide(0))
            val id = titleId(pres, 1)
            pres.replaceText(1, id, 0, 0, 5, "Copy")
            pres.setNotesText(1, "Notes of the copy")
        }
        PptxPresentation.open(f).use { pres ->
            assertEquals(3, pres.slideCount)
            assertEquals("Copy of the first slide", pres.slideView(1).shape(titleId(pres, 1))!!.text!!.plainText)
            assertEquals("Title of the first slide", pres.slideView(0).shape(titleId(pres, 0))!!.text!!.plainText)
            assertEquals("I am the notes of the first slide", pres.notesText(0))
            assertEquals("Notes of the copy", pres.notesText(1))
            // The copied notes page points at the copy, not the original.
            val notes = pres.pkg.related(pres.slidePart(1), com.vitosstudios.suite.opc.RelTypes.NOTES_SLIDE)!!
            assertEquals(pres.slidePart(1).name, pres.pkg.related(notes, com.vitosstudios.suite.opc.RelTypes.SLIDE)!!.name)
        }
        PackageCompare.assertOnlyChanged(sample, f, setOf("ppt/presentation.xml", "ppt/_rels/presentation.xml.rels", "[Content_Types].xml")) {
            it.startsWith("ppt/slides/") || it.startsWith("ppt/notesSlides/")
        }
        secondReaderSees(f, "Copy of the first slide", "Notes of the copy")
    }

    @Test
    fun duplicateSlideWithChartCopiesTheChart() {
        val src = Corpus.file("pptx/powerpoint-poi-bar-chart.pptx")
        val f = edit(src, "duplicate-chart") { pres -> pres.duplicateSlide(0) }
        PptxPresentation.open(f).use { pres ->
            val charts = (0 until 2).map { i ->
                pres.pkg.related(pres.slidePart(i), com.vitosstudios.suite.opc.RelTypes.CHART)!!.name
            }
            assertEquals(2, charts.toSet().size, "each slide owns its chart: $charts")
        }
        secondReaderSees(f)
    }

    @Test
    fun reorderSlides() {
        val f = edit(sample, "reorder") { pres -> pres.moveSlide(0, 1) }
        PptxPresentation.open(f).use { pres ->
            assertEquals("/ppt/slides/slide2.xml", pres.slidePart(0).name)
            assertEquals("/ppt/slides/slide1.xml", pres.slidePart(1).name)
        }
        PackageCompare.assertOnlyChanged(sample, f, setOf("ppt/presentation.xml"))
        secondReaderSees(f, "Title of the first slide")
    }

    @Test
    fun changeLayoutAndHide() {
        val f = edit(sample, "layout") { pres ->
            val target = pres.layouts().first { it.name == "Title Only" }
            pres.changeLayout(0, target.partName)
            pres.setSlideHidden(1, true)
        }
        PptxPresentation.open(f).use { pres ->
            assertEquals("Title Only", pres.layouts().first { it.partName == pres.slideView(0).layoutPartName }.name)
            assertTrue(pres.slideView(1).hidden)
        }
        PackageCompare.assertOnlyChanged(sample, f, setOf("ppt/slides/_rels/slide1.xml.rels", "ppt/slides/slide2.xml"))
        secondReaderSees(f, "Title of the first slide")
    }

    // ----------------------------------------------------------------------------------------
    // Shapes

    @Test
    fun insertImage() {
        var id = 0
        val f = edit(sample, "image") { pres -> id = pres.insertImage(1, TestImages.PNG_1X1, "png") }
        PptxPresentation.open(f).use { pres ->
            val pic = pres.slideView(1).shape(id)!!
            assertEquals(ShapeKind.PICTURE, pic.kind)
            assertTrue(pres.partBytes(pic.image!!.partName)!!.contentEquals(TestImages.PNG_1X1))
            assertTrue(pic.bounds.cx > 0)
        }
        PackageCompare.assertOnlyChanged(sample, f, setOf("[Content_Types].xml", "ppt/slides/slide2.xml", "ppt/slides/_rels/slide2.xml.rels")) {
            it.startsWith("ppt/media/")
        }
        secondReaderSees(f, "second")
    }

    @Test
    fun insertEachShapeTypeAndTextBox() {
        val ids = HashMap<ShapeType, Int>()
        var box = 0
        val f = edit(sample, "shapes") { pres ->
            for ((i, t) in ShapeType.entries.withIndex()) {
                ids[t] = pres.insertShape(1, t, EmuRect(500000L + i * 1500000L, 500000, 1200000, 800000))
            }
            box = pres.insertTextBox(1, EmuRect(600000, 5000000, 4000000, 600000), "Text box words")
            pres.setShapeFill(1, ids.getValue(ShapeType.RECTANGLE), 0xFF0000)
            pres.setShapeLine(1, ids.getValue(ShapeType.ELLIPSE), 0x00FF00, 38100)
        }
        PptxPresentation.open(f).use { pres ->
            val v = pres.slideView(1)
            assertEquals("rect", v.shape(ids.getValue(ShapeType.RECTANGLE))!!.geometry)
            assertEquals(FillView.Solid(0xFFFF0000.toInt()), v.shape(ids.getValue(ShapeType.RECTANGLE))!!.fill)
            assertEquals("ellipse", v.shape(ids.getValue(ShapeType.ELLIPSE))!!.geometry)
            val line = v.shape(ids.getValue(ShapeType.ELLIPSE))!!.line!!
            assertEquals(0xFF00FF00.toInt(), line.argb)
            assertEquals(38100, line.widthEmu)
            assertEquals(ShapeKind.CONNECTOR, v.shape(ids.getValue(ShapeType.LINE))!!.kind)
            assertEquals("rightArrow", v.shape(ids.getValue(ShapeType.ARROW))!!.geometry)
            assertTrue(v.shape(ids.getValue(ShapeType.ARROW))!!.fill is FillView.Solid, "style fill resolved through the theme")
            assertEquals(ShapeKind.TEXT_BOX, v.shape(box)!!.kind)
            assertEquals(5, ids.values.toSet().size + 1)
        }
        PackageCompare.assertOnlyChanged(sample, f, setOf("ppt/slides/slide2.xml"))
        secondReaderSees(f, "Text box words")
    }

    @Test
    fun moveResizeRotateAndDelete() {
        val f = edit(features, "move") { pres ->
            val title = titleId(pres, 2)
            pres.setShapeBounds(2, title, EmuRect(100, 200, 3000000, 400000), rotationDegrees = 15.0)
            val pic = pres.slideView(2).slideShapes.first { it.kind == ShapeKind.PICTURE }
            pres.deleteShape(2, pic.id)
        }
        PptxPresentation.open(f).use { pres ->
            val title = pres.slideView(2).shape(titleId(pres, 2))!!
            assertEquals(EmuRect(100, 200, 3000000, 400000), title.bounds)
            assertEquals(15.0, title.rotation, 0.001)
            assertTrue(pres.slideView(2).slideShapes.none { it.kind == ShapeKind.PICTURE })
        }
        // The picture was only used there, so its media part and relationship are gone too.
        val removed = PackageCompare.differences(features, f).filter { it.startsWith("removed ") }.map { it.substringAfter(' ') }
        assertTrue(removed.isNotEmpty() && removed.all { it.startsWith("ppt/media/") }, "$removed")
        PackageCompare.assertOnlyChanged(features, f, setOf("ppt/slides/slide3.xml", "ppt/slides/_rels/slide3.xml.rels") + removed)
        secondReaderSees(f, "Shapes and picture")
    }

    @Test
    fun animatedShapesCannotBeDeleted() {
        val src = Corpus.file("pptx/powerpoint-poi-2411-Performance_Up.pptx")
        PptxPresentation.open(src).use { pres ->
            val index = (0 until pres.slideCount).first { i -> pres.slidePart(i).xml().root.descendants().any { it.attr("spid") != null } }
            val xml = pres.slidePart(index).xml().root
            val spid = xml.descendants().first { it.attr("spid") != null }.attr("spid")!!.toInt()
            assertFailsWith<PptxEditException> { pres.deleteShape(index, spid) }
            assertFalse(pres.pkg.undo.isDirty)
        }
    }

    // ----------------------------------------------------------------------------------------
    // Notes

    @Test
    fun editExistingNotes() {
        val f = edit(sample, "notes") { pres -> pres.setNotesText(0, "Updated notes\nwith two lines") }
        PptxPresentation.open(f).use { pres -> assertEquals("Updated notes\nwith two lines", pres.notesText(0)) }
        PackageCompare.assertOnlyChanged(sample, f, setOf("ppt/notesSlides/notesSlide1.xml"))
        secondReaderSees(f, "Updated notes")
    }

    @Test
    fun createNotesInADeckWithoutNotesMaster() {
        val src = Corpus.file("pptx/powerpoint-poi-backgrounds.pptx")
        val f = edit(src, "notes-new") { pres -> pres.setNotesText(0, "Brand new speaker notes") }
        PptxPresentation.open(f).use { pres ->
            assertEquals("Brand new speaker notes", pres.notesText(0))
            assertEquals("", pres.notesText(1))
        }
        val presXml = entry(f, "ppt/presentation.xml")!!
        assertTrue(presXml.contains("notesMasterIdLst"))
        assertTrue(presXml.indexOf("notesMasterIdLst") < presXml.indexOf("sldIdLst"), "schema order")
        secondReaderSees(f, "Brand new speaker notes")
    }

    // ----------------------------------------------------------------------------------------
    // Preservation and undo

    @Test
    fun transitionsAndAnimationsSurviveEdits() {
        val src = Corpus.file("pptx/powerpoint-poi-customGeo.pptx")
        val f = edit(src, "transitions") { pres ->
            val index = (0 until pres.slideCount).first { i ->
                val v = pres.slideView(i)
                v.hasAnimations && v.hasTransition && v.slideShapes.any { it.hasEditableText && it.text!!.paragraphs.any { p -> p.text.isNotEmpty() } }
            }
            val shape = pres.slideView(index).slideShapes.first { it.hasEditableText && it.text!!.paragraphs.any { p -> p.text.isNotEmpty() } }
            val p = shape.text!!.paragraphs.indexOfFirst { it.text.isNotEmpty() }
            pres.replaceText(index, shape.id, p, 0, 0, "Edited: ")
            pres.applyRunFormat(index, shape.id, p, 0, 7, RunFormatPatch(bold = true))
        }
        val changed = PackageCompare.differences(src, f)
        assertEquals(1, changed.size, "$changed")
        val name = changed.single().substringAfter(' ')
        val before = ZipFile(src).use { z -> z.getInputStream(z.getEntry(name)).readBytes().decodeToString() }
        val after = entry(f, name)!!
        for (marker in listOf("<p:timing", "<p:transition", "mc:AlternateContent")) {
            assertEquals(before.split(marker).size, after.split(marker).size, "$marker kept")
        }
        val timing = { s: String -> s.substring(s.indexOf("<p:timing"), s.indexOf("</p:timing>")) }
        assertEquals(timing(before), timing(after), "animation XML is untouched")
        secondReaderSees(f, "Edited: ")
    }

    @Test
    fun undoRestoresTheOriginalBytes() {
        val target = out("undo")
        PptxPresentation.open(sample).use { pres ->
            val id = titleId(pres, 0)
            pres.replaceText(0, id, 0, 0, 5, "Changed")
            pres.applyRunFormat(0, id, 0, 0, 3, RunFormatPatch(bold = true))
            pres.insertSlide(2, pres.layouts()[1].partName)
            pres.duplicateSlide(0)
            pres.insertImage(0, TestImages.PNG_1X1, "png")
            pres.setNotesText(1, "x")
            pres.deleteSlide(1)
            pres.moveSlide(0, 1)
            while (pres.pkg.undo.canUndo) pres.pkg.undo.undo()
            pres.save(target)
        }
        PackageCompare.assertIdentical(sample, target)
    }

    @Test
    fun redoReappliesEdits() {
        PptxPresentation.open(sample).use { pres ->
            val id = titleId(pres, 0)
            pres.replaceText(0, id, 0, 0, 5, "Changed")
            pres.duplicateSlide(0)
            pres.pkg.undo.undo()
            pres.pkg.undo.undo()
            assertEquals(2, pres.slideCount)
            pres.pkg.undo.redo()
            pres.pkg.undo.redo()
            assertEquals(3, pres.slideCount)
            assertTrue(pres.slideView(1).shape(titleId(pres, 1))!!.text!!.plainText.startsWith("Changed"))
        }
    }

    @Test
    fun failedEditLeavesNoTrace() {
        PptxPresentation.open(sample).use { pres ->
            assertFailsWith<PptxEditException> { pres.replaceText(0, titleId(pres, 0), 0, 0, 999, "x") }
            assertFailsWith<PptxEditException> { pres.insertSlide(0, "/ppt/slides/slide1.xml") }
            assertFalse(pres.pkg.undo.isDirty)
            assertFalse(pres.pkg.undo.canUndo)
        }
    }

    // ----------------------------------------------------------------------------------------
    // Sections and custom shows

    @Test
    fun sectionsFollowSlideOperations() {
        val src = out("sections-src")
        PptxPresentation.open(features).use { pres ->
            val ids = pres.slideIdElements().map { it.attr("id")!! }
            val ext = Fragments.build(
                """<p:extLst><p:ext uri="${SlideOps.SECTION_EXT_URI}"><p14:sectionLst>""" +
                    """<p14:section name="A" id="{11111111-1111-1111-1111-111111111111}"><p14:sldIdLst><p14:sldId id="${ids[0]}"/><p14:sldId id="${ids[1]}"/></p14:sldIdLst></p14:section>""" +
                    """<p14:section name="B" id="{22222222-2222-2222-2222-222222222222}"><p14:sldIdLst><p14:sldId id="${ids[2]}"/><p14:sldId id="${ids[3]}"/></p14:sldIdLst></p14:section>""" +
                    """</p14:sectionLst></p:ext></p:extLst>""",
                pres.presentation,
            )
            pres.presentation.insertInOrder(ext, Schema.PRESENTATION)
            pres.save(src)
        }
        val f = edit(src, "sections") { pres ->
            pres.duplicateSlide(0)
            pres.insertSlide(5, pres.layouts()[0].partName)
            pres.moveSlide(4, 0)
            pres.deleteSlide(2)
        }
        PptxPresentation.open(f).use { pres ->
            val order = pres.slideIdElements().map { it.attr("id")!! }
            val listed = pres.presentation.descendants().filter { it.localName == "sldId" && it.parent?.localName == "sldIdLst" && it.parent?.parent?.localName == "section" }
                .map { it.attr("id")!! }.toList()
            assertEquals(order, listed, "every slide listed once, in slide order")
        }
        secondReaderSees(f, "Bullets")
    }

    @Test
    fun deletedSlidesLeaveCustomShows() {
        val src = out("custshow-src")
        PptxPresentation.open(sample).use { pres ->
            val rid = pres.slideIdElements()[0].attribute("r:id")!!.value
            val show = Fragments.build(
                """<p:custShowLst><p:custShow name="Short" id="0"><p:sldLst><p:sld r:id="$rid"/></p:sldLst></p:custShow></p:custShowLst>""",
                pres.presentation,
            )
            pres.presentation.insertInOrder(show, Schema.PRESENTATION)
            pres.save(src)
        }
        val f = edit(src, "custshow") { pres -> pres.deleteSlide(0) }
        assertFalse(entry(f, "ppt/presentation.xml")!!.contains("<p:sld r:id"), "dangling custom-show entry removed")
        secondReaderSees(f)
    }

    // ----------------------------------------------------------------------------------------
    // New presentations

    @Test
    fun createNewDeck() {
        val target = out("new")
        PptxPresentation.createNew().use { pres ->
            assertEquals(1, pres.slideCount)
            assertEquals(listOf("Title Slide", "Title and Content", "Title Only", "Blank"), pres.layouts().map { it.name })
            pres.replaceText(0, titleId(pres, 0), 0, 0, 0, "Fresh deck")
            for (l in pres.layouts()) pres.insertSlide(pres.slideCount, l.partName)
            val content = pres.slideView(2).slideShapes.first { it.placeholder?.index == 1 }
            pres.replaceText(2, content.id, 0, 0, 0, "Point one")
            pres.splitParagraph(2, content.id, 0, 9)
            pres.replaceText(2, content.id, 1, 0, 0, "Point two")
            pres.setNotesText(0, "New notes")
            pres.save(target)
        }
        PptxPresentation.open(target).use { pres ->
            assertEquals(5, pres.slideCount)
            val title = pres.slideView(0).shape(titleId(pres, 0))!!
            assertEquals("Fresh deck", title.text!!.plainText)
            assertEquals(60.0, title.text!!.paragraphs[0].runs[0].sizePt)
            val body = pres.slideView(2).slideShapes.first { it.placeholder?.index == 1 }.text!!.paragraphs
            assertEquals(listOf("Point one", "Point two"), body.map { it.text })
            assertEquals(BulletType.CHAR, body[0].bullet.type)
            assertEquals("New notes", pres.notesText(0))
        }
        secondReaderSees(target, "Fresh deck", "Point one", "Point two")
        if (SecondReader.isAvailable) assertNotNull(SecondReader.convert(target, "pdf"))
    }
}
