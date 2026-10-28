package com.vitosstudios.suite.pptx

import com.vitosstudios.suite.testing.Corpus
import com.vitosstudios.suite.xml.XmlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PptxReadModelTest {
    private fun open(name: String) = PptxPresentation.open(Corpus.file("pptx/$name"))

    @Test
    fun placeholderGeometryAndTextStylesAreInherited() {
        open("powerpoint-poi-SampleShow.pptx").use { pres ->
            val slide = pres.slideView(0)
            val title = slide.slideShapes.first { it.name == "The Title" }
            assertEquals(PlaceholderInfo("ctrTitle", null), title.placeholder)
            // The slide has an empty spPr: position comes from the layout.
            assertEquals(EmuRect(685800, 2130425, 7772400, 1470025), title.bounds)
            val run = title.text!!.paragraphs[0].runs[0]
            assertEquals("Title of the first slide", run.text)
            assertEquals(44.0, run.sizePt, "size from the master title style")
            assertEquals(TextAlign.CENTER, title.text!!.paragraphs[0].alignment)

            val subtitle = slide.slideShapes.first { it.name == "Another Subtitle" }
            assertEquals(EmuRect(1371600, 3886200, 6400800, 1752600), subtitle.bounds)
            val p0 = subtitle.text!!.paragraphs[0]
            assertEquals(32.0, p0.runs[0].sizePt, "size from the master body style")
            assertEquals(BulletType.NONE, p0.bullet.type, "layout removes the bullet")
            assertEquals(TextAlign.CENTER, p0.alignment)
            assertEquals(0xFF404040.toInt(), p0.runs[0].argb, "tx1 with 75 % tint from the layout")
            val green = subtitle.text!!.paragraphs[2].runs[0]
            assertTrue(green.italic)
            assertEquals(0xFF92D050.toInt(), green.argb)
            assertEquals("Calibri", green.typeface)
            assertEquals(FillView.Solid(0xFFFFFFFF.toInt()), slide.background)
        }
    }

    @Test
    fun bodyPlaceholderBulletsComeFromTheMaster() {
        open("powerpoint-poi-SampleShow.pptx").use { pres ->
            val body = pres.slideView(1).slideShapes.mapNotNull { it.text }.flatMap { it.paragraphs }
                .firstOrNull { it.bullet.type == BulletType.CHAR }
            assertNotNull(body, "second slide has a bulleted body")
            assertEquals("•", body.bullet.char)
        }
    }

    @Test
    fun lockedContentIsReported() {
        open("powerpoint-poi-bar-chart.pptx").use { pres ->
            val chart = pres.slideView(0).slideShapes.first { it.kind == ShapeKind.CHART }
            assertTrue(chart.isLocked)
            assertNotNull(chart.lockReason)
        }
        open("powerpoint-poi-smartart-simple.pptx").use { pres ->
            assertTrue(pres.slideView(0).slideShapes.any { it.kind == ShapeKind.SMARTART && it.isLocked })
        }
        open("powerpoint-poi-table_test.pptx").use { pres ->
            val table = pres.slideView(0).slideShapes.first { it.kind == ShapeKind.TABLE }
            assertTrue(table.isLocked)
            assertTrue(table.table!!.rows.isNotEmpty())
        }
    }

    @Test
    fun groupChildrenAreMappedToSlideCoordinates() {
        open("powerpoint-poi-shapes.pptx").use { pres ->
            val group = pres.slideViews().flatMap { it.slideShapes }.first { it.kind == ShapeKind.GROUP }
            assertTrue(group.children.isNotEmpty())
            for (c in group.children) {
                assertTrue(c.bounds.x >= group.bounds.x - 1 && c.bounds.right <= group.bounds.right + 1, "$c inside $group")
                assertTrue(c.bounds.y >= group.bounds.y - 1 && c.bounds.bottom <= group.bounds.bottom + 1, "$c inside $group")
            }
        }
    }

    @Test
    fun picturesAndBackgroundsResolveToParts() {
        open("pylib-features.pptx").use { pres ->
            val pic = pres.slideViews().flatMap { it.slideShapes }.first { it.kind == ShapeKind.PICTURE }
            val image = pic.image!!
            assertNotNull(pres.partBytes(image.partName))
        }
        open("powerpoint-poi-backgrounds.pptx").use { pres ->
            val backgrounds = pres.slideViews().map { it.background }
            assertTrue(backgrounds.toSet().size > 1, "backgrounds differ: $backgrounds")
        }
    }

    @Test
    fun everyDeckRendersWithoutErrors() {
        for (f in Corpus.files("pptx")) {
            PptxPresentation.open(f).use { pres ->
                for (v in pres.slideViews()) {
                    for (s in v.shapes) s.text?.paragraphs?.forEach { p -> assertEquals(p.text.length, p.runs.sumOf { it.end - it.start }) }
                }
            }
        }
    }

    @Test
    fun colorModifiers() {
        val e = XmlParser.parse("""<a:srgbClr xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" val="4F81BD"><a:lumMod val="50000"/></a:srgbClr>""").root
        val c = Colors.applyModifiers(Colors.baseColor(e)!!, e)
        // PowerPoint shows "Accent 1, darker 50 %" of 4F81BD as 243F60.
        for ((shift, expected) in listOf(16 to 0x24, 8 to 0x3F, 0 to 0x60)) {
            assertTrue(Math.abs(((c shr shift) and 0xFF) - expected) <= 2, "channel $shift of ${Integer.toHexString(c)}")
        }
        val tint = XmlParser.parse("""<a:srgbClr xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" val="000000"><a:tint val="75000"/><a:alpha val="50000"/></a:srgbClr>""").root
        assertEquals(0x80404040.toInt(), Colors.applyModifiers(Colors.baseColor(tint)!!, tint))
    }

    @Test
    fun autoNumbersAndFormats() {
        assertEquals("3.", AutoNumber.format("arabicPeriod", 3))
        assertEquals("c)", AutoNumber.format("alphaLcParenR", 3))
        assertEquals("(IV)", AutoNumber.format("romanUcParenBoth", 4))
        assertEquals("AA.", AutoNumber.format("alphaUcPeriod", 27))
    }

    @Test
    fun imageHeaders() {
        assertEquals(1 to 1, ImageInfo.size(TestImages.PNG_1X1))
        assertEquals(2 to 3, ImageInfo.size(TestImages.gif(2, 3)))
    }
}
