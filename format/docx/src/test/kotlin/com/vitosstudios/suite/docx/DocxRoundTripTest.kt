package com.vitosstudios.suite.docx

import com.vitosstudios.suite.testing.Corpus
import com.vitosstudios.suite.testing.PackageCompare
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Open with the DOCX layer, read everything the editor reads, save without edits: all parts identical. */
class DocxRoundTripTest {
    @Test
    fun everyCorpusDocumentRoundTripsByteIdentical() {
        val out = Corpus.tempDir("docx-roundtrip")
        val files = Corpus.files("docx")
        for (f in files) {
            val target = File(out, f.name)
            DocxDocument.open(f).use { d ->
                readEverything(d)
                d.save(target)
            }
            PackageCompare.assertIdentical(f, target)
        }
        assertTrue(files.size >= 20)
    }

    private fun readEverything(d: DocxDocument) {
        val paragraphs = d.allParagraphs()
        for (p in paragraphs) {
            p.text; p.spans(); p.format; p.atoms; p.lockReasons; p.headingLevel
        }
        d.listLabels()
        for (b in d.blocks()) if (b is Block.Table) {
            b.table.rows.forEach { r -> r.cells.forEach { c -> c.gridSpan; c.verticalMerge; c.paragraphs } }
            b.table.isStructureEditable
        }
        d.sections().forEach { it.pageWidthPt; it.marginLeftPt }
        d.headersFooters().forEach { it.paragraphs }
        d.compatibility()
        d.wordCount()
        d.styles.paragraphStylesForPicker()
    }
}
