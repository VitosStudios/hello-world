package com.vitosstudios.suite.xlsx

class FormulaSyntaxException(message: String) : Exception(message)

/** A reference token: a cell, an area, a whole-column or whole-row range, optionally sheet-qualified. */
data class RefSpec(
    /** Sheet name as written (unquoted), or null for the formula's own sheet. */
    val sheet: String?,
    /** Second sheet of a 3D reference ("Sheet1:Sheet3!A1"); not evaluated. */
    val sheet2: String?,
    val r1: Int, val c1: Int, val r2: Int, val c2: Int,
    val absR1: Boolean, val absC1: Boolean, val absR2: Boolean, val absC2: Boolean,
    val kind: Kind,
) {
    enum class Kind { CELL, AREA, COLUMNS, ROWS }

    /** Writes the reference back as A1 text with [sheetText] (already quoted) as prefix. */
    fun toA1(sheetText: String?): String {
        val prefix = if (sheetText != null) "$sheetText!" else ""
        fun col(c: Int, abs: Boolean) = (if (abs) "$" else "") + CellRef.columnName(c)
        fun row(r: Int, abs: Boolean) = (if (abs) "$" else "") + r
        return prefix + when (kind) {
            Kind.CELL -> col(c1, absC1) + row(r1, absR1)
            Kind.AREA -> col(c1, absC1) + row(r1, absR1) + ":" + col(c2, absC2) + row(r2, absR2)
            Kind.COLUMNS -> col(c1, absC1) + ":" + col(c2, absC2)
            Kind.ROWS -> row(r1, absR1) + ":" + row(r2, absR2)
        }
    }

    /** Moves relative parts by the offset; null if the result leaves the sheet. */
    fun shifted(dRow: Int, dCol: Int): RefSpec? {
        fun sr(r: Int, abs: Boolean) = if (abs || kind == Kind.COLUMNS) r else r + dRow
        fun sc(c: Int, abs: Boolean) = if (abs || kind == Kind.ROWS) c else c + dCol
        val nr1 = sr(r1, absR1); val nr2 = sr(r2, absR2)
        val nc1 = sc(c1, absC1); val nc2 = sc(c2, absC2)
        if (nr1 < 1 || nr2 < 1 || nc1 < 1 || nc2 < 1 || nr1 > SheetLimits.MAX_ROWS || nr2 > SheetLimits.MAX_ROWS ||
            nc1 > SheetLimits.MAX_COLS || nc2 > SheetLimits.MAX_COLS
        ) return null
        return copy(r1 = nr1, r2 = nr2, c1 = nc1, c2 = nc2)
    }
}

sealed class FToken {
    abstract val raw: String

    data class Number(override val raw: String, val value: Double) : FToken()
    data class Str(override val raw: String, val value: String) : FToken()
    data class Bool(override val raw: String, val value: Boolean) : FToken()
    data class Error(override val raw: String) : FToken()
    data class Ref(override val raw: String, val spec: RefSpec) : FToken()
    /** Function name including any `_xlfn.` prefix, as written; the `(` is a separate token. */
    data class Func(override val raw: String) : FToken()
    data class Name(override val raw: String) : FToken()
    data class Op(override val raw: String) : FToken()
    data class Space(override val raw: String) : FToken()
    data class Punct(override val raw: String) : FToken() // ( ) ,
    /** Something this engine does not evaluate (array constants, structured or external references). */
    data class Unsupported(override val raw: String) : FToken()
}

/** Splits formula text (without the leading "=") into tokens that keep their raw text. */
object FormulaLexer {
    private val ERRORS = listOf("#NULL!", "#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#NUM!", "#N/A", "#GETTING_DATA", "#SPILL!", "#CALC!", "#FIELD!", "#BLOCKED!", "#UNKNOWN!")

    fun tokenize(text: String): List<FToken> {
        val out = ArrayList<FToken>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == ' ' || c == '\n' || c == '\r' || c == '\t' -> {
                    val s = i
                    while (i < n && (text[i] == ' ' || text[i] == '\n' || text[i] == '\r' || text[i] == '\t')) i++
                    out += FToken.Space(text.substring(s, i))
                }
                c == '"' -> {
                    val sb = StringBuilder()
                    var j = i + 1
                    while (true) {
                        if (j >= n) throw FormulaSyntaxException("Unterminated string")
                        if (text[j] == '"') {
                            if (j + 1 < n && text[j + 1] == '"') { sb.append('"'); j += 2; continue }
                            break
                        }
                        sb.append(text[j]); j++
                    }
                    out += FToken.Str(text.substring(i, j + 1), sb.toString())
                    i = j + 1
                }
                c == '#' -> {
                    val e = ERRORS.firstOrNull { text.startsWith(it, i, ignoreCase = true) }
                    if (e != null) {
                        // "#REF!A1" style leftovers stay one error token.
                        out += FToken.Error(text.substring(i, i + e.length)); i += e.length
                    } else {
                        out += FToken.Unsupported(text.substring(i, minOf(n, i + 1))); i++
                    }
                }
                c == '{' || c == '[' || c == '@' -> {
                    // Array constants, structured or external references: keep raw up to the match.
                    val close = if (c == '{') '}' else if (c == '[') ']' else ' '
                    var j = i + 1
                    var depth = 1
                    if (c == '@') {
                        out += FToken.Unsupported("@"); i++; continue
                    }
                    while (j < n && depth > 0) {
                        if (text[j] == c) depth++ else if (text[j] == close) depth--
                        j++
                    }
                    // Swallow a following identifier/ref (e.g. "[1]Sheet1!A1", "Table1[Col]").
                    out += FToken.Unsupported(text.substring(i, j)); i = j
                }
                c == '(' || c == ')' || c == ',' || c == ';' -> { out += FToken.Punct(c.toString()); i++ }
                c == '<' || c == '>' -> {
                    if (i + 1 < n && (text[i + 1] == '=' || (c == '<' && text[i + 1] == '>'))) {
                        out += FToken.Op(text.substring(i, i + 2)); i += 2
                    } else { out += FToken.Op(c.toString()); i++ }
                }
                c in "+-*/^&=%" -> { out += FToken.Op(c.toString()); i++ }
                c == ':' -> { out += FToken.Op(":"); i++ }
                c == '\'' -> {
                    val r = lexQuotedSheetRef(text, i) ?: throw FormulaSyntaxException("Bad quoted reference")
                    out += r.first; i = r.second
                }
                c.isDigit() || c == '.' || c == '$' || c.isLetter() || c == '_' || c == '\\' -> {
                    val r = lexWord(text, i)
                    out += r.first; i = r.second
                }
                else -> throw FormulaSyntaxException("Unexpected character '$c'")
            }
        }
        return out
    }

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.' || c == '\\' || c == '?'

    /** 'Sheet name'!A1 or 'Sheet a:Sheet b'!A1 */
    private fun lexQuotedSheetRef(text: String, start: Int): Pair<FToken, Int>? {
        var j = start + 1
        val sb = StringBuilder()
        while (j < text.length) {
            if (text[j] == '\'') {
                if (j + 1 < text.length && text[j + 1] == '\'') { sb.append('\''); j += 2; continue }
                break
            }
            sb.append(text[j]); j++
        }
        if (j >= text.length || j + 1 >= text.length || text[j + 1] != '!') return null
        val sheetPart = sb.toString()
        val area = lexArea(text, j + 2) ?: return Pair(FToken.Unsupported(text.substring(start, j + 2)), j + 2)
        val (s1, s2) = splitSheets(sheetPart)
        return Pair(FToken.Ref(text.substring(start, area.second), area.first.copy(sheet = s1, sheet2 = s2)), area.second)
    }

    private fun splitSheets(s: String): Pair<String, String?> {
        val idx = s.indexOf(':')
        return if (idx > 0) s.substring(0, idx) to s.substring(idx + 1) else s to null
    }

    private fun lexWord(text: String, start: Int): Pair<FToken, Int> {
        val n = text.length
        // Numbers (including ".5" and exponents), unless this is a row range like "1:3".
        if (text[start].isDigit() || (text[start] == '.' && start + 1 < n && text[start + 1].isDigit())) {
            lexArea(text, start)?.let { (spec, end) -> if (spec.kind == RefSpec.Kind.ROWS) return FToken.Ref(text.substring(start, end), spec) to end }
            var j = start
            while (j < n && text[j].isDigit()) j++
            if (j < n && text[j] == '.') { j++; while (j < n && text[j].isDigit()) j++ }
            if (j < n && (text[j] == 'E' || text[j] == 'e') && j + 1 < n &&
                (text[j + 1].isDigit() || ((text[j + 1] == '+' || text[j + 1] == '-') && j + 2 < n && text[j + 2].isDigit()))
            ) {
                j += 2
                while (j < n && text[j].isDigit()) j++
            }
            val raw = text.substring(start, j)
            return FToken.Number(raw, raw.toDouble()) to j
        }
        // Plain word.
        var j = start
        while (j < n && (isNameChar(text[j]) || text[j] == '$')) j++
        val word = text.substring(start, j)
        // Structured reference: Table1[Column], Table1[[#This Row],[Col]]
        if (j < n && text[j] == '[') {
            var k = j
            var depth = 0
            while (k < n) {
                if (text[k] == '[') depth++ else if (text[k] == ']') { depth--; if (depth == 0) { k++; break } }
                k++
            }
            return FToken.Unsupported(text.substring(start, k)) to k
        }
        // Sheet-qualified reference: Sheet1!A1, Sheet1:Sheet3!A1
        if (j < n && text[j] == '!') {
            val area = lexArea(text, j + 1) ?: return FToken.Unsupported(text.substring(start, j + 1)) to j + 1
            val (s1, s2) = splitSheets(word)
            return FToken.Ref(text.substring(start, area.second), area.first.copy(sheet = s1, sheet2 = s2)) to area.second
        }
        if (j < n && text[j] == ':' && !word.contains('$') && j + 1 < n && (text[j + 1].isLetter() || text[j + 1] == '_' || text[j + 1] == '\'')) {
            // Could be a 3D prefix "Sheet1:Sheet3!A1" or an area "A1:B2" / column range "A:C".
            val k = text.indexOf('!', j)
            val area = lexArea(text, start)
            if (area != null && (k < 0 || area.second <= k)) return FToken.Ref(text.substring(start, area.second), area.first) to area.second
            if (k > j) {
                val second = text.substring(j + 1, k)
                if (second.all { isNameChar(it) }) {
                    val a2 = lexArea(text, k + 1) ?: return FToken.Unsupported(text.substring(start, k + 1)) to k + 1
                    return FToken.Ref(text.substring(start, a2.second), a2.first.copy(sheet = word, sheet2 = second)) to a2.second
                }
            }
        }
        val area = lexArea(text, start)
        if (area != null && !(area.second < n && text[area.second] == '(')) {
            return FToken.Ref(text.substring(start, area.second), area.first) to area.second
        }
        if (j < n && text[j] == '(') return FToken.Func(word) to j
        val upper = word.uppercase()
        if (upper == "TRUE" || upper == "FALSE") return FToken.Bool(word, upper == "TRUE") to j
        return FToken.Name(word) to j
    }

    private val CELL = Regex("""\G(\$?)([A-Za-z]{1,3})(\$?)(\d{1,7})""")
    private val COL = Regex("""\G(\$?)([A-Za-z]{1,3})""")
    private val ROW = Regex("""\G(\$?)(\d{1,7})""")

    /** Lexes A1, A1:B2, A:C or 1:3 at [start]; returns null if there is no reference there. */
    fun lexArea(text: String, start: Int): Pair<RefSpec, Int>? {
        fun boundary(end: Int) = end >= text.length || !(text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '.' || text[end] == '$')
        val m1 = CELL.find(text, start)?.takeIf { it.range.first == start }
        if (m1 != null) {
            val c1 = CellRef.columnIndex(m1.groupValues[2]); val r1 = m1.groupValues[4].toInt()
            var end = m1.range.last + 1
            if (c1 > SheetLimits.MAX_COLS || r1 < 1 || r1 > SheetLimits.MAX_ROWS) return null
            if (end < text.length && text[end] == ':') {
                val m2 = CELL.find(text, end + 1)?.takeIf { it.range.first == end + 1 }
                if (m2 != null && boundary(m2.range.last + 1)) {
                    val c2 = CellRef.columnIndex(m2.groupValues[2]); val r2 = m2.groupValues[4].toInt()
                    if (c2 <= SheetLimits.MAX_COLS && r2 in 1..SheetLimits.MAX_ROWS) {
                        end = m2.range.last + 1
                        return RefSpec(
                            null, null, minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2),
                            m1.groupValues[3] == "$", m1.groupValues[1] == "$", m2.groupValues[3] == "$", m2.groupValues[1] == "$",
                            RefSpec.Kind.AREA,
                        ) to end
                    }
                }
            }
            if (!boundary(end)) return null
            return RefSpec(null, null, r1, c1, r1, c1, m1.groupValues[3] == "$", m1.groupValues[1] == "$", m1.groupValues[3] == "$", m1.groupValues[1] == "$", RefSpec.Kind.CELL) to end
        }
        val col = COL.find(text, start)?.takeIf { it.range.first == start }
        if (col != null) {
            val e1 = col.range.last + 1
            if (e1 < text.length && text[e1] == ':') {
                val col2 = COL.find(text, e1 + 1)?.takeIf { it.range.first == e1 + 1 }
                if (col2 != null && boundary(col2.range.last + 1)) {
                    val a = CellRef.columnIndex(col.groupValues[2]); val b = CellRef.columnIndex(col2.groupValues[2])
                    if (a <= SheetLimits.MAX_COLS && b <= SheetLimits.MAX_COLS) {
                        return RefSpec(null, null, 1, minOf(a, b), SheetLimits.MAX_ROWS, maxOf(a, b), true, col.groupValues[1] == "$", true, col2.groupValues[1] == "$", RefSpec.Kind.COLUMNS) to col2.range.last + 1
                    }
                }
            }
        }
        val row = ROW.find(text, start)?.takeIf { it.range.first == start }
        if (row != null) {
            val e1 = row.range.last + 1
            if (e1 < text.length && text[e1] == ':') {
                val row2 = ROW.find(text, e1 + 1)?.takeIf { it.range.first == e1 + 1 }
                if (row2 != null && boundary(row2.range.last + 1)) {
                    val a = row.groupValues[2].toInt(); val b = row2.groupValues[2].toInt()
                    if (a in 1..SheetLimits.MAX_ROWS && b in 1..SheetLimits.MAX_ROWS) {
                        return RefSpec(null, null, minOf(a, b), 1, maxOf(a, b), SheetLimits.MAX_COLS, row.groupValues[1] == "$", true, row2.groupValues[1] == "$", true, RefSpec.Kind.ROWS) to row2.range.last + 1
                    }
                }
            }
        }
        return null
    }

    /** Quotes a sheet name for use in a formula when needed. */
    fun quoteSheet(name: String): String {
        val simple = name.isNotEmpty() && (name[0].isLetter() || name[0] == '_') && name.all { it.isLetterOrDigit() || it == '_' || it == '.' }
        val looksLikeRef = CellRef.parseOrNull(name) != null || Regex("^[Rr]\\d*[Cc]\\d*$").matches(name) ||
            name.equals("TRUE", true) || name.equals("FALSE", true)
        return if (simple && !looksLikeRef) name else "'" + name.replace("'", "''") + "'"
    }
}
