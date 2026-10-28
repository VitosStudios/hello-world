package com.vitosstudios.suite.opc

import com.vitosstudios.suite.testing.Corpus
import com.vitosstudios.suite.testing.PackageCompare
import com.vitosstudios.suite.xml.XmlCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Package-level round trip over the whole corpus: open, save without edits, compare every part.
 * A second pass parses every XML part (as the editors would) and checks that parse + serialise
 * is lossless.
 */
class PackageRoundTripTest {
    private val all: List<File> = listOf("docx", "xlsx", "pptx").flatMap { Corpus.files(it, includeLarge = false) } +
        Corpus.files("docx").filter { it.parentFile.name == "large" } + Corpus.files("xlsx").filter { it.parentFile.name == "large" }

    @Test
    fun saveWithoutEditsIsByteIdenticalPerPart() {
        val out = Corpus.tempDir("opc-roundtrip")
        for (f in all) {
            val target = File(out, f.name)
            OpcPackage.open(f).use { pkg ->
                assertNotNull(pkg.mainDocument(), "${f.name}: no main document")
                pkg.save(target)
            }
            PackageCompare.assertIdentical(f, target)
        }
    }

    @Test
    fun everyXmlPartParsesLosslessly() {
        val failures = ArrayList<String>()
        var count = 0
        for (f in all) {
            OpcPackage.open(f).use { pkg ->
                for (p in pkg.parts) {
                    if (p.isDirectory || !PackageVerifier.isXml(p)) continue
                    count++
                    val bytes = p.bytes()
                    try {
                        assertContentEquals(bytes, XmlCodec.serialize(XmlCodec.parse(bytes)))
                    } catch (e: Throwable) {
                        failures += "${f.name}:${p.name}: ${e.message}"
                    }
                }
            }
        }
        if (failures.isNotEmpty()) fail("Lossless parse failed for ${failures.size}/$count parts:\n" + failures.joinToString("\n"))
        assertTrue(count > 500, "Expected many XML parts, got $count")
    }

    @Test
    fun parsedButUnchangedPartsAreStillIdentical() {
        val out = Corpus.tempDir("opc-parsed")
        for (f in all) {
            val target = File(out, f.name)
            OpcPackage.open(f).use { pkg ->
                pkg.parts.filter { !it.isDirectory && PackageVerifier.isXml(it) }.forEach { it.xml() }
                pkg.save(target)
            }
            PackageCompare.assertIdentical(f, target)
        }
    }
}
