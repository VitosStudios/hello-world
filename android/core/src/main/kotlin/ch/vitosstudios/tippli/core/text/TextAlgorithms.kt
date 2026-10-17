package ch.vitosstudios.tippli.core.text

import ch.vitosstudios.tippli.core.text.Unicode.isWhitespace

/** Result of [TextAlgorithms.selectSegment] (SPEC §5.2). */
data class Segment(val context: String, val segment: String, val trailing: String)

/** A text replacement right before the caret: delete [delete], then insert [insert] (SPEC §5.4). */
data class TextEdit(val delete: String, val insert: String)

/**
 * The text algorithms of SPEC §5.2–5.6. They must produce exactly the results
 * of `shared/tools/reference_engine.py` for the cases in
 * `shared/tests/engine-cases.json`.
 */
object TextAlgorithms {

    const val MAX_SEGMENT = 400
    const val MAX_CONTEXT = 600

    private val QUOTE_PAIRS = listOf(
        "\"" to "\"", "„" to "“", "“" to "”", "«" to "»", "'" to "'", "‘" to "’",
    )
    private const val WORD_EXTRA = "'’-_"
    private const val CLOSERS = ")]}\"'»”’"

    private fun isWordChar(cp: Int) = Unicode.isLetterOrNumber(cp) || (cp < 0x10000 && WORD_EXTRA.indexOf(cp.toChar()) >= 0)

    /**
     * SPEC §5.2. Returns null when there is nothing to correct. [minStart] is the
     * earliest index the segment may start at (undo protection, SPEC §5.5).
     */
    fun selectSegment(
        before: String,
        explicit: Boolean,
        pinyin: Boolean,
        minStart: Int = 0,
        maxSegment: Int = MAX_SEGMENT,
        maxContext: Int = MAX_CONTEXT,
    ): Segment? {
        // Step 0: never start inside a word – skip to after the next whitespace.
        var min = minStart.coerceIn(0, before.length)
        if (min > 0 && min < before.length && !isWhitespace(before[min - 1])) {
            while (min < before.length && !isWhitespace(before[min])) min++
            min = minOf(min + 1, before.length)
        }
        val p = maxOf(before.lastIndexOf('\n') + 1, min)
        var end = before.length
        var trailing = ""
        val inProgress = before.isNotEmpty() && Unicode.isLetterOrNumber(Unicode.lastCodePoint(before))
        if (!explicit && !pinyin && inProgress) {
            var k = -1
            for (i in before.length - 1 downTo 0) {
                if (isWhitespace(before[i])) { k = i; break }
            }
            if (k < p) return null
            end = k + 1
            trailing = before.substring(end)
        }
        val segStart: Int
        if (end - p > maxSegment) {
            val window = end - maxSegment
            var start: Int? = null
            for (i in window until end) {
                if (isWhitespace(before[i]) && i - 1 >= 0 && Unicode.isSentenceEnd(before[i - 1])) { start = i + 1; break }
            }
            if (start == null) {
                for (i in window until end) {
                    if (isWhitespace(before[i])) { start = i + 1; break }
                }
            }
            var s = start ?: window
            // Never cut a surrogate pair in half.
            if (s in 1 until before.length && Character.isLowSurrogate(before[s]) && Character.isHighSurrogate(before[s - 1])) s++
            segStart = s
        } else {
            segStart = p
        }
        val segment = before.substring(segStart, end)
        if (!Unicode.hasLetter(segment)) return null
        var ctxStart = maxOf(0, segStart - maxContext)
        if (ctxStart > 0) {
            for (i in ctxStart until segStart) {
                if (isWhitespace(before[i])) { ctxStart = i + 1; break }
            }
        }
        return Segment(context = before.substring(ctxStart, segStart), segment = segment, trailing = trailing)
    }

    fun leadingWhitespace(s: String): String {
        var i = 0
        while (i < s.length && isWhitespace(s[i])) i++
        return s.substring(0, i)
    }

    fun trailingWhitespace(s: String): String {
        var i = s.length
        while (i > 0 && isWhitespace(s[i - 1])) i--
        return s.substring(i)
    }

    private fun pyStrip(s: String): String = s.trim { isWhitespace(it) }

    /** SPEC §5.4 step 2. Returns null when the model output must be dropped. */
    fun cleanOutput(segment: String, output: String, pinyin: Boolean): String? {
        var t = output
        val open = t.indexOf("<segment>")
        if (open >= 0) t = t.substring(open + "<segment>".length)
        val close = t.indexOf("</segment>")
        if (close >= 0) t = t.substring(0, close)
        t = pyStrip(t)
        if (t.startsWith("```")) {
            val nl = t.indexOf('\n')
            t = if (nl >= 0) t.substring(nl + 1) else t.substring(3)
            if (t.endsWith("```")) t = t.substring(0, t.length - 3)
            t = pyStrip(t)
        }
        val sTrim = pyStrip(segment)
        for ((o, c) in QUOTE_PAIRS) {
            if (Unicode.codePointCount(t) >= 2 && t.startsWith(o) && t.endsWith(c) && !sTrim.startsWith(o)) {
                t = pyStrip(t.substring(o.length, t.length - c.length))
                break
            }
        }
        if (t.isEmpty()) return null
        if (t.contains("<context>") || t.contains("</context>")) return null
        val segLen = Unicode.codePointCount(sTrim)
        if (!pinyin && segLen >= 12) {
            val outLen = Unicode.codePointCount(t)
            if (outLen > segLen * 1.5 + 10 || outLen < segLen * 0.5 - 5) return null
        }
        return leadingWhitespace(segment) + t + trailingWhitespace(segment)
    }

    /** Length of the common prefix of [a] and [b], moved back to a grapheme boundary of both. */
    fun commonPrefixLength(a: String, b: String): Int {
        var p = 0
        val max = minOf(a.length, b.length)
        while (p < max && a[p] == b[p]) p++
        while (p > 0 && !(Unicode.isGraphemeBoundary(a, p) && Unicode.isGraphemeBoundary(b, p))) p--
        return p
    }

    /** SPEC §5.4 step 5. Returns null when nothing has to change. */
    fun computeEdit(segment: String, corrected: String, suffix: String): TextEdit? {
        if (segment == corrected) return null
        val p = commonPrefixLength(segment, corrected)
        return TextEdit(delete = segment.substring(p) + suffix, insert = corrected.substring(p) + suffix)
    }

    /** SPEC §5.6: the text a swipe-left (or word backspace) removes. */
    fun wordDelete(before: String): String {
        val n = before.length
        if (n == 0) return ""
        if (before[n - 1] == '\n') return "\n"
        var i = n
        while (i > 0 && isWhitespace(before[i - 1]) && before[i - 1] != '\n') i--
        if (i == 0 || before[i - 1] == '\n') return before.substring(i)
        var j = i
        val last = Character.codePointBefore(before, i)
        if (isWordChar(last)) {
            while (j > 0) {
                val cp = Character.codePointBefore(before, j)
                if (!isWordChar(cp)) break
                j -= Character.charCount(cp)
            }
        } else {
            while (j > 0) {
                val cp = Character.codePointBefore(before, j)
                if ((cp < 0x10000 && isWhitespace(cp.toChar())) || Unicode.isLetterOrNumber(cp)) break
                j -= Character.charCount(cp)
            }
        }
        return before.substring(j)
    }

    /** SPEC §5.6: should the next letter be capitalised? */
    fun autoCapitalize(before: String): Boolean {
        if (before.isEmpty()) return true
        val lastChar = before[before.length - 1]
        if (lastChar == '\n') return true
        if (!isWhitespace(lastChar)) return false
        val t = before.trimEnd { isWhitespace(it) }
        if (t.isEmpty()) return true
        val last = t[t.length - 1]
        if (last == '\n') return true
        return Unicode.isSentenceEnd(last)
    }

    /** SPEC §5.6: called on a second space within DOUBLE_SPACE; true → replace the last space with ". ". */
    fun doubleSpacePeriod(before: String): Boolean {
        if (before.length < 2 || before[before.length - 1] != ' ') return false
        val cp = Character.codePointBefore(before, before.length - 1)
        return Unicode.isLetterOrNumber(cp) || (cp < 0x10000 && CLOSERS.indexOf(cp.toChar()) >= 0)
    }

    /** SPEC §5.4 step 4. */
    fun canApply(beforeNow: String, segment: String, trailing: String, typedSince: String): Boolean =
        beforeNow.endsWith(segment + trailing + typedSince)

    /** True if [text] contains a sentence end or a newline (SPEC §4.3, §5.3). */
    fun containsSentenceEnd(text: CharSequence): Boolean = text.any { it == '\n' || Unicode.isSentenceEnd(it) }
}
