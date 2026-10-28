package com.vitosstudios.suite.xml

import com.vitosstudios.suite.common.UndoManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XmlRoundTripTest {
    private fun roundTrip(xml: String) {
        val bytes = xml.toByteArray(Charsets.UTF_8)
        val doc = XmlCodec.parse(bytes)
        assertContentEquals(bytes, XmlCodec.serialize(doc), "Round trip changed:\n$xml")
    }

    @Test
    fun keepsEverySourceDetail() {
        roundTrip("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\r\n" +
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"  xmlns:mc='urn:mc'""" +
            """ mc:Ignorable="w14"><w:body><w:p w:rsidR="00A1"   ><w:r><w:t xml:space="preserve"> A &amp; B &#x2014; &#169; </w:t></w:r></w:p>""" +
            """<w:p></w:p><w:p/><w:sectPr ><w:pgSz w:w = "12240" w:h="15840" /></w:sectPr></w:body ></w:document>""")
    }

    @Test
    fun keepsCommentsPisCdataDoctypeAndEpilog() {
        roundTrip("<?xml version='1.0'?>\n<!-- head --><!DOCTYPE r [<!ENTITY x \"y\">]>\n<?pi data?>\n" +
            "<r a='1'\tb=\"2\"\n><![CDATA[<raw> & ]]><!--c--><?x y?>text &unknown; &lt;</r>\n<!-- tail -->\n")
    }

    @Test
    fun keepsBomAndUtf16() {
        val text = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><a>é€</a>"
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertContentEquals(utf16, XmlCodec.serialize(XmlCodec.parse(utf16)))
        val utf8bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<a>x</a>".toByteArray()
        assertContentEquals(utf8bom, XmlCodec.serialize(XmlCodec.parse(utf8bom)))
    }

    @Test
    fun rejectsMalformedInput() {
        assertFailsWith<XmlParseException> { XmlCodec.parse("<a><b></a>".toByteArray()) }
        assertFailsWith<XmlParseException> { XmlCodec.parse("<a>".toByteArray()) }
        assertFailsWith<XmlParseException> { XmlCodec.parse("<a x='1'y='2'/>".toByteArray()) }
        assertFailsWith<XmlParseException> { XmlCodec.parse(byteArrayOf(0x3C, 0x61, 0x3E, 0xC3.toByte(), 0x3C, 0x2F, 0x61, 0x3E)) }
    }

    @Test
    fun deepNestingDoesNotOverflow() {
        val depth = 50_000
        val xml = "<a>".repeat(depth) + "</a>".repeat(depth)
        roundTrip(xml)
    }

    @Test
    fun namespaceLookupsAndStrictAliases() {
        val doc = XmlParser.parse("""<x:root xmlns:x="http://purl.oclc.org/ooxml/wordprocessingml/main"><x:p x:val="1" plain="2"><child xmlns="urn:d"/></x:p></x:root>""")
        val p = doc.root.element(Namespaces.W, "p")!!
        assertEquals("1", p.attr(Namespaces.W, "val"))
        assertEquals("2", p.attr("plain"))
        assertNull(p.attr(Namespaces.W, "plain"))
        assertEquals("urn:d", p.elements().first().ns)
        // A new element in a strict document reuses the strict prefix.
        val r = p.createChild(Namespaces.W, "r")
        assertEquals("x:r", r.qName)
    }

    @Test
    fun mutationsAreUndoable() {
        val original = """<a xmlns:w="${Namespaces.W}"><w:b w:val="1"/>text</a>"""
        val doc = XmlParser.parse(original)
        val undo = UndoManager()
        doc.changeSink = undo::record
        undo.transaction("edit") {
            val b = doc.root.element(Namespaces.W, "b")!!
            b.setAttr(Namespaces.W, "val", "0")
            b.setAttr(null, "new", "x<y")
            doc.root.appendChild(XElement("w:i"))
            (doc.root.children[1] as XText).text = "a & b"
        }
        assertEquals("""<a xmlns:w="${Namespaces.W}"><w:b w:val="0" new="x&lt;y"/>a &amp; b<w:i/></a>""", doc.toXmlString())
        undo.undo()
        assertEquals(original, doc.toXmlString())
        undo.redo()
        assertTrue(doc.toXmlString().contains("<w:i/>"))
    }

    @Test
    fun failedTransactionRollsBack() {
        val original = "<a><b/></a>"
        val doc = XmlParser.parse(original)
        val undo = UndoManager()
        doc.changeSink = undo::record
        assertFailsWith<IllegalStateException> {
            undo.transaction("broken") {
                doc.root.appendChild(XElement("c"))
                error("boom")
            }
        }
        assertEquals(original, doc.toXmlString())
        assertEquals(false, undo.canUndo)
    }

    @Test
    fun insertInOrderRespectsSchemaSequence() {
        val doc = XmlParser.parse("""<w:rPr xmlns:w="${Namespaces.W}"><w:rStyle w:val="x"/><w:sz w:val="20"/><w:foo/></w:rPr>""")
        val order = listOf("rStyle", "rFonts", "b", "bCs", "i", "iCs", "sz", "szCs")
        doc.root.insertInOrder(doc.root.createChild(Namespaces.W, "b"), order)
        doc.root.insertInOrder(doc.root.createChild(Namespaces.W, "szCs"), order)
        assertEquals("""<w:rPr xmlns:w="${Namespaces.W}"><w:rStyle w:val="x"/><w:b/><w:sz w:val="20"/><w:szCs/><w:foo/></w:rPr>""", doc.toXmlString())
    }

    @Test
    fun createChildDeclaresMissingNamespace() {
        val doc = XmlParser.parse("<root/>")
        val c = doc.root.createChild(Namespaces.A, "ext")
        doc.root.appendChild(c)
        assertEquals("""<root><a:ext xmlns:a="${Namespaces.A}"/></root>""", doc.toXmlString())
        assertEquals(Namespaces.A, c.ns)
    }

    @Test
    fun copyWithNamespacesIsSelfContained() {
        val doc = XmlParser.parse("""<r xmlns:w="${Namespaces.W}"><w:p><w:r/></w:p></r>""")
        val copy = doc.root.element(Namespaces.W, "p")!!.deepCopyWithNamespaces()
        assertEquals("""<w:p xmlns:w="${Namespaces.W}"><w:r/></w:p>""", copy.toString())
    }

    @Test
    fun decodesEntities() {
        assertEquals("<&>\"'©x&foo;", XmlEscaping.decode("&lt;&amp;&gt;&quot;&apos;&#169;&#x78;&foo;"))
    }
}
