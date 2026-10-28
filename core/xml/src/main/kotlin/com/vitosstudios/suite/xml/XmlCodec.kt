package com.vitosstudios.suite.xml

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Byte-level entry points: encoding detection, byte order marks and lossless serialisation. */
object XmlCodec {
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    fun parse(bytes: ByteArray): XDocument {
        var offset = 0
        var bom: ByteArray? = null
        var charset: Charset
        when {
            bytes.startsWith(UTF8_BOM) -> { bom = UTF8_BOM; offset = 3; charset = Charsets.UTF_8 }
            bytes.startsWith(UTF16LE_BOM) -> { bom = UTF16LE_BOM; offset = 2; charset = Charsets.UTF_16LE }
            bytes.startsWith(UTF16BE_BOM) -> { bom = UTF16BE_BOM; offset = 2; charset = Charsets.UTF_16BE }
            bytes.size >= 4 && bytes[0] == 0x3C.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0x3F.toByte() && bytes[3] == 0.toByte() ->
                charset = Charsets.UTF_16LE
            bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0x3C.toByte() && bytes[2] == 0.toByte() && bytes[3] == 0x3F.toByte() ->
                charset = Charsets.UTF_16BE
            else -> charset = declaredCharset(bytes) ?: Charsets.UTF_8
        }
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (e: CharacterCodingException) {
            throw XmlParseException("Invalid ${charset.name()} byte sequence")
        }
        val doc = XmlParser.parse(text)
        doc.charset = charset
        doc.byteOrderMark = bom
        return doc
    }

    fun serialize(doc: XDocument): ByteArray {
        val body = doc.toXmlString().toByteArray(doc.charset)
        val bom = doc.byteOrderMark ?: return body
        return bom + body
    }

    /**
     * Parses [bytes] and checks that serialising the unmodified tree gives the same bytes back.
     * Callers use this before editing a part: a part that does not survive the check is kept
     * read-only and written back untouched.
     */
    fun parseVerified(bytes: ByteArray): XDocument {
        val doc = parse(bytes)
        if (!serialize(doc).contentEquals(bytes)) throw XmlParseException("Part does not survive a lossless round trip")
        return doc
    }

    private fun declaredCharset(bytes: ByteArray): Charset? {
        // The declaration is ASCII-compatible for every encoding we could meet here.
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        if (!head.startsWith("<?xml")) return null
        val end = head.indexOf("?>")
        if (end < 0) return null
        val m = Regex("""encoding\s*=\s*["']([A-Za-z0-9._\-]+)["']""").find(head.substring(0, end)) ?: return null
        return try { Charset.forName(m.groupValues[1]) } catch (e: Exception) { null }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (k in prefix.indices) if (this[k] != prefix[k]) return false
        return true
    }
}
