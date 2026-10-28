package com.vitosstudios.suite.opc

import com.vitosstudios.suite.testing.Corpus
import com.vitosstudios.suite.testing.PackageCompare
import com.vitosstudios.suite.xml.XElement
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackageModelTest {
    private val docx = Corpus.file("docx/word-poi-sample.docx")

    @Test
    fun partNames() {
        assertEquals("/word/_rels/document.xml.rels", PartNames.relsPartFor("/word/document.xml"))
        assertEquals("/_rels/.rels", PartNames.relsPartFor(null))
        assertEquals("/word/document.xml", PartNames.sourceOfRelsPart("/word/_rels/document.xml.rels"))
        assertEquals("/word/media/image1.png", PartNames.resolve("/word/document.xml", "media/image1.png"))
        assertEquals("/customXml/item1.xml", PartNames.resolve("/word/document.xml", "../customXml/item1.xml"))
        assertEquals("/xl/workbook.xml", PartNames.resolve(null, "xl/workbook.xml"))
        assertEquals("/ppt/slides/slide1.xml", PartNames.resolve("/ppt/presentation.xml", "/ppt/slides/slide1.xml"))
        assertEquals("media/image1.png", PartNames.relativize("/word/document.xml", "/word/media/image1.png"))
        assertEquals("../media/image2.png", PartNames.relativize("/ppt/slides/slide1.xml", "/ppt/media/image2.png"))
        assertEquals("word/document.xml", PartNames.relativize(null, "/word/document.xml"))
    }

    @Test
    fun relationshipsAndContentTypes() {
        OpcPackage.open(docx).use { pkg ->
            val main = pkg.mainDocument()!!
            assertEquals("/word/document.xml", main.name)
            assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml", main.contentType)
            val styles = pkg.related(main, RelTypes.STYLES)
            assertNotNull(styles)
            assertEquals("application/xml", pkg.contentTypes.defaultFor("xml"))
        }
    }

    @Test
    fun addPartWithRelationshipThenUndo() {
        val out = Corpus.tempDir("opc-model")
        OpcPackage.open(docx).use { pkg ->
            val main = pkg.mainDocument()!!
            val before = pkg.relationships(main).all().size
            pkg.undo.transaction("Insert image") {
                val name = pkg.uniquePartName("/word/media/image", "png")
                pkg.addPart(name, ContentTypeNames.PNG, byteArrayOf(1, 2, 3))
                pkg.contentTypes.ensureDefault("png", ContentTypeNames.PNG)
                pkg.relationships(main).addTo(RelTypes.IMAGE, name)
            }
            assertEquals(before + 1, pkg.relationships(main).all().size)
            val rel = pkg.relationships(main).ofType(RelTypes.IMAGE).last()
            assertContentEquals(byteArrayOf(1, 2, 3), pkg.relationships(main).targetPart(rel)!!.bytes())
            val saved = File(out, "with-image.docx")
            pkg.save(saved)
            PackageCompare.assertOnlyChanged(docx, saved, setOf("[Content_Types].xml", "word/_rels/document.xml.rels")) { it.startsWith("word/media/") }

            assertTrue(pkg.undo.undo())
            assertEquals(before, pkg.relationships(main).all().size)
            assertNull(pkg.part(rel.target.let { PartNames.resolve(main.name, it) }))
            val reverted = File(out, "reverted.docx")
            pkg.save(reverted)
            PackageCompare.assertIdentical(docx, reverted)
        }
    }

    @Test
    fun removePartRecursivelyKeepsSharedTargets() {
        val pptx = Corpus.file("pptx/powerpoint-poi-sample.pptx")
        OpcPackage.open(pptx).use { pkg ->
            val pres = pkg.mainDocument()!!
            val slideRel = pkg.relationships(pres).ofType(RelTypes.SLIDE).first()
            val slide = pkg.relationships(pres).targetPart(slideRel)!!
            val layout = pkg.related(slide, RelTypes.SLIDE_LAYOUT)!!
            pkg.removePartRecursively(slide.name)
            assertNull(pkg.part(slide.name))
            assertNotNull(pkg.part(layout.name), "Layout is shared with the master and must stay")
            pkg.undo.undo()
            assertNotNull(pkg.part(slide.name))
        }
    }

    @Test
    fun failedSaveLeavesOriginalUntouched() {
        val out = Corpus.tempDir("opc-atomic")
        val target = File(out, "target.docx")
        docx.copyTo(target, overwrite = true)
        val originalBytes = target.readBytes()
        OpcPackage.open(docx).use { pkg ->
            // Verification failure.
            assertFailsWith<IllegalStateException> { pkg.save(target) { error("rejected by verifier") } }
            assertContentEquals(originalBytes, target.readBytes())
            // Corrupt content: the main part is no longer XML, the structural verifier rejects it.
            pkg.mainDocument()!!.setBytes("not xml".toByteArray())
            assertFailsWith<Exception> { pkg.save(target) }
            assertContentEquals(originalBytes, target.readBytes())
        }
        assertTrue(out.listFiles()!!.none { it.name.endsWith(".partial") }, "Temporary files must be cleaned up")
    }

    @Test
    fun crashDuringWriteLeavesOriginalUntouched() {
        val out = Corpus.tempDir("opc-crash")
        val target = File(out, "target.docx")
        docx.copyTo(target, overwrite = true)
        val originalBytes = target.readBytes()
        assertFailsWith<IOException> {
            com.vitosstudios.suite.common.AtomicFile.write(target) { os ->
                os.write(ByteArray(1000))
                throw IOException("simulated crash")
            }
        }
        assertContentEquals(originalBytes, target.readBytes())
    }

    @Test
    fun savingOverTheSourceKeepsWorking() {
        val out = Corpus.tempDir("opc-overwrite")
        val target = File(out, "doc.docx")
        docx.copyTo(target, overwrite = true)
        OpcPackage.open(target).use { pkg ->
            val body = pkg.mainDocument()!!.xml().root.elements().first()
            body.appendChild(XElement("w:p"))
            pkg.save(target)
            assertFalse(pkg.undo.isDirty)
            body.appendChild(XElement("w:p"))
            pkg.save(target)
        }
        OpcPackage.open(target).use { pkg ->
            val body = pkg.mainDocument()!!.xml().root.elements().first()
            assertTrue(body.children.takeLast(2).all { it is XElement && it.qName == "w:p" })
        }
        PackageCompare.assertOnlyChanged(docx, target, setOf("word/document.xml"))
    }

    @Test
    fun rejectsNonPackages() {
        assertFailsWith<OpcException> { OpcPackage.open("hello".toByteArray()) }
        val out = Corpus.tempDir("opc-bad")
        val f = File(out, "bad.docx").apply { writeText("not a zip") }
        assertFailsWith<OpcException> { OpcPackage.open(f) }
    }
}

class PackageSnapshotTest {
    @Test
    fun snapshotSavedOnAnotherThreadMatchesDirectSave() {
        val docx = Corpus.file("docx/word-poi-sample.docx")
        val out = Corpus.tempDir("opc-snapshot")
        val work = File(out, "work.docx")
        docx.copyTo(work, overwrite = true)
        OpcPackage.open(work).use { pkg ->
            val body = pkg.mainDocument()!!.xml().root.elements().first()
            body.appendChild(XElement("w:p"))
            val snap = pkg.snapshot()
            // Edits after the snapshot are not part of it.
            body.appendChild(XElement("w:p"))
            val t = Thread { snap.save(work) }
            t.start(); t.join()
            assertTrue(pkg.undo.isDirty)
            pkg.undo.markSaved(snap.undoMarker)
            assertTrue(pkg.undo.isDirty, "the second edit is still unsaved")
            // The package keeps working after its source file was replaced.
            pkg.save(File(out, "direct.docx"))
        }
        OpcPackage.open(work).use { pkg ->
            val body = pkg.mainDocument()!!.xml().root.elements().first()
            assertEquals(1, body.children.count { it is XElement && it.qName == "w:p" && (it as XElement).children.isEmpty() })
        }
        PackageCompare.assertOnlyChanged(docx, work, setOf("word/document.xml"))
    }
}
