package ch.vitosstudios.tippli.core.text

/**
 * Small Unicode helpers. Kotlin strings are UTF-16, but character classes
 * must be decided per code point (emoji and many CJK characters are
 * surrogate pairs), so everything here works on code points.
 */
object Unicode {

    const val ZWJ = 0x200D

    /** Sentence end characters (SPEC §5.6). */
    const val SENTENCE_END_CHARS = ".!?…。！？"

    fun isSentenceEnd(c: Char): Boolean = SENTENCE_END_CHARS.indexOf(c) >= 0

    fun isWhitespace(c: Char): Boolean = c.isWhitespace()

    fun isLetter(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(),
        Character.TITLECASE_LETTER.toInt(), Character.MODIFIER_LETTER.toInt(),
        Character.OTHER_LETTER.toInt() -> true
        else -> false
    }

    /** Letter or number in the Unicode sense (categories L* and N*), like Python's reference. */
    fun isLetterOrNumber(cp: Int): Boolean = isLetter(cp) || when (Character.getType(cp)) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(),
        Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    fun hasLetter(s: CharSequence): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            if (isLetter(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /** Code point that ends right before [index], or -1. */
    fun codePointBefore(s: CharSequence, index: Int): Int =
        if (index <= 0) -1 else Character.codePointBefore(s, index)

    fun lastCodePoint(s: CharSequence): Int = codePointBefore(s, s.length)

    fun codePointCount(s: CharSequence): Int = Character.codePointCount(s, 0, s.length)

    private fun isExtender(cp: Int): Boolean {
        if (cp == ZWJ) return true
        if (cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF) return true // variation selectors
        if (cp in 0x1F3FB..0x1F3FF) return true // emoji skin tone modifiers
        if (cp in 0xE0020..0xE007F) return true // tag characters (subdivision flags)
        if (cp == 0x20E3) return true // combining enclosing keycap
        return when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt() -> true
            else -> false
        }
    }

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    /**
     * True when [index] is a grapheme cluster boundary in [s]. Covers surrogate
     * pairs, combining marks, variation selectors, skin tones, ZWJ sequences and
     * flag pairs – the cases that matter for typed text and emoji.
     */
    fun isGraphemeBoundary(s: CharSequence, index: Int): Boolean {
        if (index <= 0 || index >= s.length) return true
        if (Character.isLowSurrogate(s[index]) && Character.isHighSurrogate(s[index - 1])) return false
        val next = Character.codePointAt(s, index)
        val prev = Character.codePointBefore(s, index)
        if (isExtender(next)) return false
        if (prev == ZWJ) return false
        if (isRegionalIndicator(prev) && isRegionalIndicator(next)) {
            // Boundary only after an even number of regional indicators.
            var count = 0
            var i = index
            while (i > 0) {
                val cp = Character.codePointBefore(s, i)
                if (!isRegionalIndicator(cp)) break
                count++
                i -= Character.charCount(cp)
            }
            return count % 2 == 0
        }
        if (prev == '\r'.code && next == '\n'.code) return false
        return true
    }

    /** Length (in chars) of the last grapheme cluster of [s]; 0 for an empty string. */
    fun lastGraphemeLength(s: CharSequence): Int {
        if (s.isEmpty()) return 0
        var i = s.length - Character.charCount(Character.codePointBefore(s, s.length))
        while (i > 0 && !isGraphemeBoundary(s, i)) {
            i -= Character.charCount(Character.codePointBefore(s, i))
        }
        return s.length - i
    }

    /** Length (in chars) of the first grapheme cluster of [s]. */
    fun firstGraphemeLength(s: CharSequence): Int {
        if (s.isEmpty()) return 0
        var i = Character.charCount(Character.codePointAt(s, 0))
        while (i < s.length && !isGraphemeBoundary(s, i)) {
            i += Character.charCount(Character.codePointAt(s, i))
        }
        return i
    }

    /** Upper-cases a key or accent text; `ß` becomes `ẞ` (SPEC §4.4). */
    fun upper(text: String): String = when (text) {
        "ß" -> "ẞ"
        else -> text.uppercase()
    }
}
