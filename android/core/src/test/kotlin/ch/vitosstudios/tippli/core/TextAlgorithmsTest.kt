package ch.vitosstudios.tippli.core

import ch.vitosstudios.tippli.core.text.TextAlgorithms
import ch.vitosstudios.tippli.core.text.Unicode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Additional checks beyond the shared vectors: grapheme handling on UTF-16 strings. */
class TextAlgorithmsTest {

    @Test
    fun computeEditNeverSplitsEmojiOrFlags() {
        // Same high surrogate, different low surrogate: the prefix must not end inside the pair.
        val edit = TextAlgorithms.computeEdit("ok 😀 ", "ok 😃 ", "")!!
        assertEquals("😀 ", edit.delete)
        assertEquals("😃 ", edit.insert)
        // 🇨🇭 vs 🇨🇦 share the first regional indicator.
        val flag = TextAlgorithms.computeEdit("hoi 🇨🇭", "hoi 🇨🇦", "")!!
        assertEquals("🇨🇭", flag.delete)
        assertEquals("🇨🇦", flag.insert)
        // Combining marks stay with their base letter.
        val combining = TextAlgorithms.computeEdit("café x", "cafè x", "")!!
        assertEquals("é x", combining.delete)
    }

    @Test
    fun graphemeLengths() {
        assertEquals(2, Unicode.lastGraphemeLength("a😀"))
        assertEquals(1, Unicode.lastGraphemeLength("ab"))
        assertEquals(4, Unicode.lastGraphemeLength("x🇨🇭"))
        assertEquals("👍🏽".length, Unicode.lastGraphemeLength("a👍🏽"))
        assertEquals("😮‍💨".length, Unicode.lastGraphemeLength("😮‍💨"))
        assertEquals(2, Unicode.lastGraphemeLength("é"))
        assertEquals(0, Unicode.lastGraphemeLength(""))
    }

    @Test
    fun wordDeleteWithSupplementaryCharacters() {
        assertEquals("😀😀", TextAlgorithms.wordDelete("ok 😀😀"))
        assertEquals("𠀀𠀁", TextAlgorithms.wordDelete("x 𠀀𠀁")) // CJK extension B letters
    }

    @Test
    fun selectSegmentTreatsSupplementaryLettersAsWords() {
        // A CJK Ext-B letter at the end is a word in progress.
        assertNull(TextAlgorithms.selectSegment("𠀀", explicit = false, pinyin = false))
        assertEquals("𠀀", TextAlgorithms.selectSegment("𠀀", explicit = true, pinyin = false)?.segment)
    }

    @Test
    fun selectSegmentMinStartIsClampedAndNeverInsideAWord() {
        val before = "alt alt neu wort "
        assertEquals("neu wort ", TextAlgorithms.selectSegment(before, false, false, minStart = 8)?.segment)
        assertEquals("neu wort ", TextAlgorithms.selectSegment(before, false, false, minStart = 6)?.segment) // inside "alt"
        assertNull(TextAlgorithms.selectSegment(before, false, false, minStart = before.length))
        assertNull(TextAlgorithms.selectSegment(before, false, false, minStart = 999))
        assertEquals(before, TextAlgorithms.selectSegment(before, false, false, minStart = -3)?.segment)
    }

    @Test
    fun upperCase() {
        assertEquals("ẞ", Unicode.upper("ß"))
        assertEquals("Ä", Unicode.upper("ä"))
        assertEquals("，", Unicode.upper("，"))
    }

    @Test
    fun sentenceEnds() {
        assertTrue(TextAlgorithms.containsSentenceEnd("ok."))
        assertTrue(TextAlgorithms.containsSentenceEnd("\n"))
        assertTrue(TextAlgorithms.containsSentenceEnd("好。"))
        assertFalse(TextAlgorithms.containsSentenceEnd("ok, "))
    }
}
