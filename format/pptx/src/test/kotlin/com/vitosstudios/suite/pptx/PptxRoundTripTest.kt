package com.vitosstudios.suite.pptx

import com.vitosstudios.suite.testing.Corpus
import com.vitosstudios.suite.testing.PackageCompare
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every corpus deck: open with the PPTX layer, build the full read model (every slide, notes,
 * layouts, compatibility report), save without edits and compare part by part.
 */
class PptxRoundTripTest {
    @Test
    fun openReadAndSaveIsByteIdentical() {
        val out = Corpus.tempDir("pptx-roundtrip")
        val files = Corpus.files("pptx")
        assertTrue(files.size >= 15)
        for (f in files) {
            val target = File(out, f.name)
            PptxPresentation.open(f).use { pres ->
                val views = pres.slideViews()
                assertEquals(pres.slideCount, views.size, f.name)
                for (i in views.indices) pres.notesText(i)
                pres.layouts()
                pres.compatibility()
                pres.save(target)
            }
            PackageCompare.assertIdentical(f, target)
        }
    }

    @Test
    fun everySlideHasShapesWithSaneGeometry() {
        for (f in Corpus.files("pptx")) {
            PptxPresentation.open(f).use { pres ->
                val (w, h) = pres.slideSize
                assertTrue(w > 0 && h > 0, f.name)
                for (v in pres.slideViews()) {
                    for (s in v.slideShapes) {
                        assertTrue(s.bounds.cx >= 0 && s.bounds.cy >= 0, "${f.name} slide ${v.index} shape ${s.name}: ${s.bounds}")
                    }
                }
            }
        }
    }
}
