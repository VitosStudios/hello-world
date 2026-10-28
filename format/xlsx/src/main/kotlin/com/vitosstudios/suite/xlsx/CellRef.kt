package com.vitosstudios.suite.xlsx

/** Spreadsheet limits (Office Open XML). */
object SheetLimits {
    const val MAX_ROWS = 1_048_576
    const val MAX_COLS = 16_384
}

/** A 1-based cell address. */
data class CellRef(val row: Int, val col: Int) : Comparable<CellRef> {
    init {
        require(row >= 1 && col >= 1) { "Invalid cell $row,$col" }
    }

    val a1: String get() = columnName(col) + row

    override fun compareTo(other: CellRef): Int = if (row != other.row) row.compareTo(other.row) else col.compareTo(other.col)
    override fun toString(): String = a1

    companion object {
        private val A1 = Regex("""^\$?([A-Za-z]{1,3})\$?(\d{1,7})$""")

        fun parse(a1: String): CellRef {
            val m = A1.matchEntire(a1.trim()) ?: throw IllegalArgumentException("Not a cell reference: $a1")
            return CellRef(m.groupValues[2].toInt(), columnIndex(m.groupValues[1]))
        }

        fun parseOrNull(a1: String): CellRef? = try { parse(a1) } catch (e: IllegalArgumentException) { null }

        /** 1 -> "A", 27 -> "AA". */
        fun columnName(col: Int): String {
            require(col >= 1) { "Invalid column $col" }
            var c = col
            val sb = StringBuilder()
            while (c > 0) {
                val rem = (c - 1) % 26
                sb.append('A' + rem)
                c = (c - 1) / 26
            }
            return sb.reverse().toString()
        }

        /** "A" -> 1, "aa" -> 27. */
        fun columnIndex(name: String): Int {
            var n = 0
            for (ch in name) {
                val u = ch.uppercaseChar()
                require(u in 'A'..'Z') { "Invalid column name $name" }
                n = n * 26 + (u - 'A' + 1)
            }
            return n
        }

        /** Fast parser for the `r` attribute of a `<c>` element; returns null if malformed. */
        fun parseCellAttr(s: String): CellRef? {
            var i = 0
            var col = 0
            while (i < s.length && s[i].isLetter()) {
                col = col * 26 + (s[i].uppercaseChar() - 'A' + 1)
                i++
            }
            if (i == 0 || i == s.length) return null
            var row = 0
            while (i < s.length) {
                val c = s[i]
                if (c !in '0'..'9') return null
                row = row * 10 + (c - '0')
                i++
            }
            return if (row >= 1 && col >= 1) CellRef(row, col) else null
        }
    }
}

/** An inclusive rectangular range of cells. */
data class CellRange(val first: CellRef, val last: CellRef) {
    constructor(r1: Int, c1: Int, r2: Int, c2: Int) : this(CellRef(minOf(r1, r2), minOf(c1, c2)), CellRef(maxOf(r1, r2), maxOf(c1, c2)))

    val top: Int get() = first.row
    val left: Int get() = first.col
    val bottom: Int get() = last.row
    val right: Int get() = last.col
    val rowCount: Int get() = bottom - top + 1
    val colCount: Int get() = right - left + 1
    val isSingleCell: Boolean get() = first == last

    operator fun contains(ref: CellRef): Boolean = ref.row in top..bottom && ref.col in left..right
    fun contains(row: Int, col: Int): Boolean = row in top..bottom && col in left..right
    fun intersects(o: CellRange): Boolean = !(o.left > right || o.right < left || o.top > bottom || o.bottom < top)

    fun union(o: CellRange): CellRange = CellRange(minOf(top, o.top), minOf(left, o.left), maxOf(bottom, o.bottom), maxOf(right, o.right))

    val a1: String get() = if (isSingleCell) first.a1 else "${first.a1}:${last.a1}"

    /** Absolute form, e.g. `$A$1:$B$4`. */
    val absoluteA1: String
        get() {
            val a = "$" + CellRef.columnName(left) + "$" + top
            return if (isSingleCell) a else a + ":$" + CellRef.columnName(right) + "$" + bottom
        }

    override fun toString(): String = a1

    companion object {
        fun of(ref: CellRef) = CellRange(ref, ref)

        fun parse(text: String): CellRange {
            val t = text.trim()
            val idx = t.indexOf(':')
            if (idx < 0) return CellRef.parse(t).let { CellRange(it, it) }
            val a = parseEnd(t.substring(0, idx), start = true)
            val b = parseEnd(t.substring(idx + 1), start = false)
            return CellRange(a.row, a.col, b.row, b.col)
        }

        /** Handles whole-column ("A:A") and whole-row ("3:3") ends. */
        private fun parseEnd(s: String, start: Boolean): CellRef {
            val c = s.replace("$", "")
            if (c.isNotEmpty() && c.all { it.isLetter() }) return CellRef(if (start) 1 else SheetLimits.MAX_ROWS, CellRef.columnIndex(c))
            if (c.isNotEmpty() && c.all { it.isDigit() }) return CellRef(c.toInt(), if (start) 1 else SheetLimits.MAX_COLS)
            return CellRef.parse(c)
        }

        fun parseOrNull(text: String): CellRange? = try { parse(text) } catch (e: Exception) { null }

        /** A space-separated list of ranges (`sqref`). */
        fun parseList(sqref: String): List<CellRange> =
            sqref.trim().split(' ', '\t', '\n').filter { it.isNotEmpty() }.mapNotNull { parseOrNull(it) }
    }
}
