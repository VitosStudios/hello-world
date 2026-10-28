package com.vitosstudios.suite.docx

import com.vitosstudios.suite.opc.ContentTypeNames
import com.vitosstudios.suite.opc.RelTypes
import com.vitosstudios.suite.xml.XElement
import com.vitosstudios.suite.xml.XmlParser

/** One level of a list definition. */
data class ListLevel(
    val ilvl: Int,
    val start: Int,
    /** ST_NumberFormat: decimal, bullet, lowerLetter, upperRoman, ... */
    val format: String,
    val text: String,
    val indentLeftPt: Float,
    val hangingPt: Float,
    val bulletFont: String?,
) {
    val isBullet: Boolean get() = format == "bullet"
}

/** The numbering part (word/numbering.xml): list definitions, labels and list editing. */
class DocxNumbering internal constructor(private val doc: DocxDocument) {
    private val root: XElement? get() = doc.pkg.related(doc.mainPart, RelTypes.NUMBERING)?.xmlOrNull()?.root

    private fun num(numId: String): XElement? = root?.ws("num")?.firstOrNull { it.wAttr("numId") == numId }
    private fun abstractNum(id: String): XElement? = root?.ws("abstractNum")?.firstOrNull { it.wAttr("abstractNumId") == id }

    /** Level definition for [numId]/[ilvl], following lvlOverride and numStyleLink. */
    fun level(numId: String, ilvl: Int): ListLevel? {
        if (numId == "0") return null
        val n = num(numId) ?: return null
        n.ws("lvlOverride").firstOrNull { it.wAttr("ilvl")?.toIntOrNull() == ilvl }?.w("lvl")?.let { return parseLevel(it, ilvl) }
        var abs = n.w("abstractNumId")?.wAttr("val")?.let(::abstractNum) ?: return null
        abs.w("numStyleLink")?.wAttr("val")?.let { styleId ->
            val styleNumId = doc.styles.chain(styleId).lastOrNull()?.w("pPr")?.w("numPr")?.w("numId")?.wAttr("val")
            val linked = styleNumId?.let(::num)?.w("abstractNumId")?.wAttr("val")?.let(::abstractNum)
            if (linked != null) abs = linked
        }
        val lvl = abs.ws("lvl").firstOrNull { it.wAttr("ilvl")?.toIntOrNull() == ilvl } ?: return null
        val base = parseLevel(lvl, ilvl)
        val startOverride = n.ws("lvlOverride").firstOrNull { it.wAttr("ilvl")?.toIntOrNull() == ilvl }
            ?.w("startOverride")?.wAttr("val")?.toIntOrNull()
        return if (startOverride != null) base.copy(start = startOverride) else base
    }

    private fun parseLevel(lvl: XElement, ilvl: Int): ListLevel {
        val ind = lvl.w("pPr")?.w("ind")
        return ListLevel(
            ilvl = ilvl,
            start = lvl.w("start")?.wAttr("val")?.toIntOrNull() ?: 1,
            format = lvl.w("numFmt")?.wAttr("val") ?: "decimal",
            text = lvl.w("lvlText")?.wAttr("val") ?: "",
            indentLeftPt = Units.twipsToPoints((ind?.wAttr("left") ?: ind?.wAttr("start"))?.toIntOrNull() ?: (720 * (ilvl + 1))),
            hangingPt = Units.twipsToPoints(ind?.wAttr("hanging")?.toIntOrNull() ?: 360),
            bulletFont = lvl.w("rPr")?.w("rFonts")?.wAttr("ascii"),
        )
    }

    /**
     * List labels ("1.", "a)", "•") for [paragraphs] in document order. Counters are kept per
     * list instance and reset for deeper levels when a shallower level advances.
     */
    fun labels(paragraphs: List<Paragraph>): Map<Paragraph, String> {
        val out = HashMap<Paragraph, String>()
        val counters = HashMap<String, IntArray>()
        for (p in paragraphs) {
            val f = p.format
            val numId = f.numId ?: continue
            val ilvl = f.ilvl.coerceIn(0, 8)
            val lvl = level(numId, ilvl) ?: continue
            val c = counters.getOrPut(numId) { IntArray(9) { -1 } }
            c[ilvl] = if (c[ilvl] < 0) lvl.start else c[ilvl] + 1
            for (deeper in ilvl + 1..8) c[deeper] = -1
            out[p] = formatLabel(numId, lvl, c)
        }
        return out
    }

    private fun formatLabel(numId: String, lvl: ListLevel, counters: IntArray): String {
        if (lvl.isBullet) return bulletChar(lvl.text, lvl.bulletFont)
        if (lvl.format == "none") return lvl.text.replace(Regex("%[1-9]"), "")
        return Regex("%([1-9])").replace(lvl.text) { m ->
            val i = m.groupValues[1].toInt() - 1
            val l = if (i == lvl.ilvl) lvl else level(numId, i)
            val value = if (counters[i] < 0) (l?.start ?: 1) else counters[i]
            formatNumber(value, l?.format ?: "decimal")
        }
    }

    // ------------------------------------------------------------------------------------
    // Editing

    /** Puts [p] into a bulleted or numbered list (undoable). */
    fun applyList(p: Paragraph, numbered: Boolean, previous: Paragraph?) {
        doc.pkg.undo.transaction(if (numbered) "Numbered list" else "Bulleted list") {
            val prevFmt = previous?.format
            val prevNum = prevFmt?.numId
            val numId = if (prevNum != null && level(prevNum, 0)?.isBullet == !numbered) prevNum else findOrCreateNum(numbered)
            setNumPr(p, numId, if (prevNum == numId) prevFmt.ilvl else 0)
        }
    }

    /** Removes [p] from its list (undoable). */
    fun removeList(p: Paragraph) {
        doc.pkg.undo.transaction("Remove list") {
            val direct = p.pPr?.w("numPr")
            val fromStyle = doc.styles.chain(p.styleId).any { it.w("pPr")?.w("numPr") != null }
            direct?.detach()
            if (fromStyle) p.setStyle(null)
        }
    }

    /** Changes the list level of [p] by [delta] (undoable). */
    fun changeLevel(p: Paragraph, delta: Int) {
        val f = p.format
        val numId = f.numId ?: return
        val target = (f.ilvl + delta).coerceIn(0, 8)
        if (target == f.ilvl) return
        doc.pkg.undo.transaction("List level") { setNumPr(p, numId, target) }
    }

    fun isBulleted(p: Paragraph): Boolean = p.format.numId?.let { level(it, p.format.ilvl)?.isBullet } == true
    fun isNumbered(p: Paragraph): Boolean = p.format.numId?.let { id -> level(id, p.format.ilvl)?.let { !it.isBullet } } == true

    private fun setNumPr(p: Paragraph, numId: String, ilvl: Int) {
        val pPr = p.ensurePPr()
        val numPr = pPr.getOrCreate(Wml.W, "numPr", Wml.PPR_ORDER)
        numPr.getOrCreate(Wml.W, "ilvl", listOf("ilvl", "numId", "numberingChange", "ins")).setAttr(Wml.W, "val", ilvl.toString())
        numPr.getOrCreate(Wml.W, "numId", listOf("ilvl", "numId", "numberingChange", "ins")).setAttr(Wml.W, "val", numId)
    }

    private fun findOrCreateNum(numbered: Boolean): String {
        val r = root ?: createPart()
        // Reuse an existing definition with the wanted kind of level 0.
        for (n in r.ws("num")) {
            val id = n.wAttr("numId") ?: continue
            val l = level(id, 0) ?: continue
            if (l.isBullet != numbered && (l.isBullet || l.format == "decimal")) {
                if (!numbered) return id
                // A new numbered list restarts at 1: new instance of the same definition.
                val absId = n.w("abstractNumId")?.wAttr("val") ?: continue
                return addNum(r, absId, restart = true)
            }
        }
        val absId = addAbstractNum(r, numbered)
        return addNum(r, absId, restart = false)
    }

    private fun addAbstractNum(r: XElement, numbered: Boolean): String {
        val id = ((r.ws("abstractNum").mapNotNull { it.wAttr("abstractNumId")?.toIntOrNull() }.maxOrNull() ?: -1) + 1).toString()
        val bullets = listOf("•", "◦", "▪")
        val formats = listOf("decimal", "lowerLetter", "lowerRoman")
        val xml = buildString {
            append("""<w:abstractNum xmlns:w="${Wml.W}" w:abstractNumId="$id"><w:multiLevelType w:val="hybridMultilevel"/>""")
            for (l in 0..8) {
                val left = 720 * (l + 1)
                append("""<w:lvl w:ilvl="$l"><w:start w:val="1"/>""")
                if (numbered) {
                    append("""<w:numFmt w:val="${formats[l % 3]}"/><w:lvlText w:val="%${l + 1}."/>""")
                } else {
                    append("""<w:numFmt w:val="bullet"/><w:lvlText w:val="${bullets[l % 3]}"/>""")
                }
                append("""<w:lvlJc w:val="left"/><w:pPr><w:ind w:left="$left" w:hanging="360"/></w:pPr></w:lvl>""")
            }
            append("</w:abstractNum>")
        }
        val e = XmlParser.parse(xml).root.deepCopy()
        stripRedundantNamespace(e, r)
        r.insertInOrder(e, Wml.NUMBERING_ORDER)
        return id
    }

    private fun addNum(r: XElement, abstractId: String, restart: Boolean): String {
        val id = ((r.ws("num").mapNotNull { it.wAttr("numId")?.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
        val xml = buildString {
            append("""<w:num xmlns:w="${Wml.W}" w:numId="$id"><w:abstractNumId w:val="$abstractId"/>""")
            if (restart) append("""<w:lvlOverride w:ilvl="0"><w:startOverride w:val="1"/></w:lvlOverride>""")
            append("</w:num>")
        }
        val e = XmlParser.parse(xml).root.deepCopy()
        stripRedundantNamespace(e, r)
        r.insertInOrder(e, Wml.NUMBERING_ORDER)
        return id
    }

    private fun createPart(): XElement {
        val name = "/word/numbering.xml".let { if (doc.pkg.part(it) == null) it else doc.pkg.uniquePartName("/word/numbering", "xml", 2) }
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\r\n" + """<w:numbering xmlns:w="${Wml.W}"></w:numbering>"""
        doc.pkg.addXmlPart(name, ContentTypeNames.WML_NUMBERING, XmlParser.parse(xml))
        doc.pkg.relationships(doc.mainPart).addTo(RelTypes.NUMBERING, name)
        return doc.pkg.part(name)!!.xml().root
    }

    companion object {
        fun formatNumber(n: Int, format: String): String = when (format) {
            "decimalZero" -> if (n < 10) "0$n" else n.toString()
            "upperRoman" -> roman(n)
            "lowerRoman" -> roman(n).lowercase()
            "upperLetter" -> letters(n)
            "lowerLetter" -> letters(n).lowercase()
            "ordinal" -> n.toString() + when {
                n % 100 in 11..13 -> "th"
                n % 10 == 1 -> "st"
                n % 10 == 2 -> "nd"
                n % 10 == 3 -> "rd"
                else -> "th"
            }
            "decimalEnclosedCircle" -> if (n in 1..20) (0x2460 + n - 1).toChar().toString() else n.toString()
            "none" -> ""
            else -> n.toString()
        }

        private fun roman(n: Int): String {
            if (n <= 0 || n >= 4000) return n.toString()
            val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var x = n
            val sb = StringBuilder()
            for (i in values.indices) while (x >= values[i]) { sb.append(symbols[i]); x -= values[i] }
            return sb.toString()
        }

        /** Word repeats the letter: A..Z, AA..ZZ, AAA... */
        private fun letters(n: Int): String {
            if (n <= 0) return n.toString()
            val letter = 'A' + (n - 1) % 26
            return letter.toString().repeat((n - 1) / 26 + 1)
        }

        /** Maps bullet glyphs from symbol fonts to Unicode. */
        fun bulletChar(text: String, font: String?): String {
            if (text.isEmpty()) return "•"
            val c = text[0].code
            val code = if (c in 0xF000..0xF0FF) c - 0xF000 else c
            return when {
                font.equals("Symbol", true) && code == 0xB7 -> "•"
                font?.startsWith("Wingdings", true) == true && code == 0xA7 -> "▪"
                font?.startsWith("Wingdings", true) == true && code == 0xD8 -> "➢"
                font?.startsWith("Wingdings", true) == true && code == 0xFC -> "✓"
                font?.startsWith("Wingdings", true) == true && code == 0x76 -> "❖"
                font.equals("Courier New", true) && text == "o" -> "◦"
                c in 0xF000..0xF0FF -> "•"
                else -> text
            }
        }
    }
}
