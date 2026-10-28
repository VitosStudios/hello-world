package com.vitosstudios.suite.xlsx

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/** Values produced by formula evaluation. */
sealed class FValue {
    data class Num(val v: Double) : FValue()
    data class Str(val s: String) : FValue()
    data class Bool(val b: Boolean) : FValue()
    data class Err(val code: String) : FValue()
    object Blank : FValue() { override fun toString() = "Blank" }

    /** A rectangular reference, evaluated lazily and sparsely. */
    class Area(val sheet: String?, val r1: Int, val c1: Int, val r2: Int, val c2: Int, private val ctx: EvalContext) : FValue() {
        val rows: Int get() = r2 - r1 + 1
        val cols: Int get() = c2 - c1 + 1
        fun get(row: Int, col: Int): FValue = ctx.cellValue(sheet, row, col)
        /** Non-blank cells in row-major order. */
        fun cells(): Sequence<FValue> = ctx.nonBlankCells(sheet, r1, c1, r2, c2)
    }

    companion object {
        val DIV0 = Err("#DIV/0!")
        val NA = Err("#N/A")
        val NAME = Err("#NAME?")
        val REF = Err("#REF!")
        val VALUE = Err("#VALUE!")
        val NUM = Err("#NUM!")
    }
}

/** What the evaluator needs from the workbook. Sheet null means the formula's own sheet. */
interface EvalContext {
    fun cellValue(sheet: String?, row: Int, col: Int): FValue
    fun nonBlankCells(sheet: String?, r1: Int, c1: Int, r2: Int, c2: Int): Sequence<FValue>
    /** Expression for a defined name, or null if unknown. */
    fun resolveName(name: String): FNode? = null
    fun sheetExists(sheet: String): Boolean = true
    val date1904: Boolean get() = false
}

/**
 * Evaluates parsed formulas with spreadsheet semantics. [isSupported] tells whether a formula only
 * uses what this engine implements; formulas that do not are left to their cached values.
 */
object FormulaEvaluator {
    val FUNCTIONS: Set<String> = setOf(
        "SUM", "AVERAGE", "MIN", "MAX", "COUNT", "COUNTA", "IF", "ROUND", "ROUNDUP", "ROUNDDOWN", "VLOOKUP",
        "CONCAT", "CONCATENATE", "ABS", "AND", "OR", "NOT", "IFERROR", "LEN", "UPPER", "LOWER", "TRIM",
        "INT", "MOD", "PRODUCT", "SQRT", "POWER", "LEFT", "RIGHT", "MID", "TEXT", "ISBLANK", "ISNUMBER",
        "ISTEXT", "ISERROR",
    )

    private val ARITY: Map<String, IntRange> = mapOf(
        "IF" to 1..3, "ROUND" to 2..2, "ROUNDUP" to 2..2, "ROUNDDOWN" to 2..2, "VLOOKUP" to 3..4, "ABS" to 1..1,
        "NOT" to 1..1, "IFERROR" to 2..2, "LEN" to 1..1, "UPPER" to 1..1, "LOWER" to 1..1, "TRIM" to 1..1,
        "INT" to 1..1, "MOD" to 2..2, "SQRT" to 1..1, "POWER" to 2..2, "LEFT" to 1..2, "RIGHT" to 1..2,
        "MID" to 3..3, "TEXT" to 2..2, "ISBLANK" to 1..1, "ISNUMBER" to 1..1, "ISTEXT" to 1..1, "ISERROR" to 1..1,
    )

    /** Canonical function name: strips `_xlfn.` from functions this engine knows. */
    fun canonicalFunction(name: String): String {
        val u = name.uppercase()
        return if (u.startsWith("_XLFN.")) u.substring(6) else u
    }

    /** Whether every function, name and construct in [node] can be evaluated here. */
    fun isSupported(node: FNode, ctx: EvalContext? = null, depth: Int = 0): Boolean = when (node) {
        is FNode.Unsupported -> false
        is FNode.Name -> ctx != null && depth < 8 && ctx.resolveName(node.name)?.let { isSupported(it, ctx, depth + 1) } == true
        is FNode.Ref -> node.spec.sheet2 == null && (node.spec.sheet == null || ctx == null || ctx.sheetExists(node.spec.sheet))
        is FNode.Func -> {
            val n = canonicalFunction(node.name)
            n in FUNCTIONS && (ARITY[n]?.contains(node.args.size) ?: node.args.isNotEmpty()) &&
                !node.name.uppercase().startsWith("_XLUDF.") && node.args.all { isSupported(it, ctx, depth) }
        }
        is FNode.Unary -> isSupported(node.e, ctx, depth)
        is FNode.Binary -> isSupported(node.l, ctx, depth) && isSupported(node.r, ctx, depth)
        is FNode.Percent -> isSupported(node.e, ctx, depth)
        else -> true
    }

    /**
     * Evaluates [node] for the cell at ([row], [col]). [dRow]/[dCol] move relative references,
     * which is how shared formulas are evaluated for their dependent cells.
     */
    fun evaluate(node: FNode, ctx: EvalContext, row: Int, col: Int, dRow: Int = 0, dCol: Int = 0): FValue {
        val e = Eval(ctx, row, col, dRow, dCol)
        val v = e.eval(node)
        return when (v) {
            is FValue.Area -> e.intersect(v)
            else -> v
        }.let { if (it is FValue.Blank) FValue.Num(0.0) else it }
    }

    private class Eval(val ctx: EvalContext, val row: Int, val col: Int, val dRow: Int, val dCol: Int) {
        private var nameDepth = 0

        fun eval(n: FNode): FValue = when (n) {
            is FNode.Num -> FValue.Num(n.v)
            is FNode.Str -> FValue.Str(n.s)
            is FNode.Bool -> FValue.Bool(n.b)
            is FNode.Err -> FValue.Err(n.code)
            is FNode.Missing -> FValue.Blank
            is FNode.Unsupported -> FValue.NAME
            is FNode.Ref -> ref(n.spec)
            is FNode.Name -> {
                val target = ctx.resolveName(n.name)
                if (target == null || nameDepth > 8) FValue.NAME
                else { nameDepth++; try { Eval(ctx, row, col, 0, 0).eval(target) } finally { nameDepth-- } }
            }
            is FNode.Percent -> num(scalar(n.e)).let { if (it is FValue.Num) FValue.Num(it.v / 100.0) else it }
            is FNode.Unary -> {
                if (n.op == "+") {
                    // Unary plus does not convert its operand.
                    eval(n.e)
                } else {
                    val v = num(scalar(n.e))
                    if (v is FValue.Num) FValue.Num(-v.v) else v
                }
            }
            is FNode.Binary -> binary(n)
            is FNode.Func -> call(n)
        }

        private fun ref(spec: RefSpec): FValue {
            val s = if (dRow != 0 || dCol != 0) spec.shifted(dRow, dCol) ?: return FValue.REF else spec
            if (s.sheet != null && !ctx.sheetExists(s.sheet)) return FValue.REF
            return if (s.kind == RefSpec.Kind.CELL) ctx.cellValue(s.sheet, s.r1, s.c1)
            else FValue.Area(s.sheet, s.r1, s.c1, s.r2, s.c2, ctx)
        }

        /** Implicit intersection of an area with the formula's row or column. */
        fun intersect(a: FValue.Area): FValue = when {
            a.rows == 1 && a.cols == 1 -> a.get(a.r1, a.c1)
            a.cols == 1 && row in a.r1..a.r2 -> a.get(row, a.c1)
            a.rows == 1 && col in a.c1..a.c2 -> a.get(a.r1, col)
            else -> FValue.VALUE
        }

        fun scalar(n: FNode): FValue = eval(n).let { if (it is FValue.Area) intersect(it) else it }

        private fun binary(n: FNode.Binary): FValue {
            val l = scalar(n.l)
            val r = scalar(n.r)
            if (n.op == "&") {
                if (l is FValue.Err) return l
                if (r is FValue.Err) return r
                return FValue.Str(text(l) + text(r))
            }
            if (n.op in setOf("=", "<>", "<", ">", "<=", ">=")) {
                if (l is FValue.Err) return l
                if (r is FValue.Err) return r
                val c = compare(l, r)
                return FValue.Bool(
                    when (n.op) {
                        "=" -> c == 0
                        "<>" -> c != 0
                        "<" -> c < 0
                        ">" -> c > 0
                        "<=" -> c <= 0
                        else -> c >= 0
                    },
                )
            }
            val a = num(l); if (a is FValue.Err) return a
            val b = num(r); if (b is FValue.Err) return b
            val x = (a as FValue.Num).v
            val y = (b as FValue.Num).v
            return when (n.op) {
                "+" -> FValue.Num(x + y)
                "-" -> FValue.Num(x - y)
                "*" -> FValue.Num(x * y)
                "/" -> if (y == 0.0) FValue.DIV0 else FValue.Num(x / y)
                "^" -> if (x == 0.0 && y == 0.0) FValue.NUM else x.pow(y).let { if (it.isNaN() || it.isInfinite()) FValue.NUM else FValue.Num(it) }
                else -> FValue.VALUE
            }
        }

        // ------------------------------------------------------------------------------------
        // Functions

        private fun call(f: FNode.Func): FValue {
            val name = canonicalFunction(f.name)
            val a = f.args
            return when (name) {
                "SUM" -> aggregate(a) { it.sum() }
                "PRODUCT" -> aggregate(a) { list -> if (list.isEmpty()) 0.0 else list.fold(1.0) { acc, d -> acc * d } }
                "AVERAGE" -> aggregate(a) { if (it.isEmpty()) Double.NaN else it.sum() / it.size }.let {
                    if (it is FValue.Num && it.v.isNaN()) FValue.DIV0 else it
                }
                "MIN" -> aggregate(a) { it.minOrNull() ?: 0.0 }
                "MAX" -> aggregate(a) { it.maxOrNull() ?: 0.0 }
                "COUNT" -> FValue.Num(count(a).toDouble())
                "COUNTA" -> FValue.Num(countA(a).toDouble())
                "IF" -> {
                    val c = bool(scalar(a[0]))
                    if (c is FValue.Err) c
                    else if ((c as FValue.Bool).b) (if (a.size >= 2) branch(a[1]) else FValue.Bool(true))
                    else if (a.size >= 3) branch(a[2]) else FValue.Bool(false)
                }
                "IFERROR" -> scalar(a[0]).let { if (it is FValue.Err) branch(a[1]) else it }
                "ROUND", "ROUNDUP", "ROUNDDOWN" -> {
                    val x = num(scalar(a[0])); if (x is FValue.Err) return x
                    val d = num(scalar(a[1])); if (d is FValue.Err) return d
                    val mode = when (name) { "ROUND" -> RoundingMode.HALF_UP; "ROUNDUP" -> RoundingMode.UP; else -> RoundingMode.DOWN }
                    FValue.Num(roundTo((x as FValue.Num).v, (d as FValue.Num).v.toInt(), mode))
                }
                "VLOOKUP" -> vlookup(a)
                "CONCAT" -> {
                    val sb = StringBuilder()
                    for (arg in a) {
                        val v = eval(arg)
                        if (v is FValue.Area) {
                            for (r in v.r1..v.r2) for (c in v.c1..v.c2) {
                                val cv = v.get(r, c)
                                if (cv is FValue.Err) return cv
                                sb.append(text(cv))
                            }
                        } else {
                            if (v is FValue.Err) return v
                            sb.append(text(v))
                        }
                    }
                    FValue.Str(sb.toString())
                }
                "CONCATENATE" -> {
                    val sb = StringBuilder()
                    for (arg in a) {
                        val v = scalar(arg)
                        if (v is FValue.Err) return v
                        sb.append(text(v))
                    }
                    FValue.Str(sb.toString())
                }
                "ABS" -> num1(a) { abs(it) }
                "INT" -> num1(a) { floor(it) }
                "SQRT" -> num(scalar(a[0])).let { if (it is FValue.Num) (if (it.v < 0) FValue.NUM else FValue.Num(sqrt(it.v))) else it }
                "POWER" -> eval(FNode.Binary("^", a[0], a[1]))
                "MOD" -> {
                    val x = num(scalar(a[0])); if (x is FValue.Err) return x
                    val y = num(scalar(a[1])); if (y is FValue.Err) return y
                    val n = (x as FValue.Num).v; val d = (y as FValue.Num).v
                    if (d == 0.0) FValue.DIV0 else FValue.Num(n - d * floor(n / d))
                }
                "AND", "OR" -> logical(a, name == "AND")
                "NOT" -> bool(scalar(a[0])).let { if (it is FValue.Bool) FValue.Bool(!it.b) else it }
                "LEN" -> str1(a) { FValue.Num(it.length.toDouble()) }
                "UPPER" -> str1(a) { FValue.Str(it.uppercase()) }
                "LOWER" -> str1(a) { FValue.Str(it.lowercase()) }
                "TRIM" -> str1(a) { FValue.Str(it.trim(' ').replace(Regex(" {2,}"), " ")) }
                "LEFT", "RIGHT" -> {
                    val s = scalar(a[0]); if (s is FValue.Err) return s
                    val nv = if (a.size > 1) num(scalar(a[1])) else FValue.Num(1.0)
                    if (nv is FValue.Err) return nv
                    val k = (nv as FValue.Num).v.toInt()
                    if (k < 0) return FValue.VALUE
                    val t = text(s)
                    FValue.Str(if (name == "LEFT") t.take(k) else t.takeLast(k))
                }
                "MID" -> {
                    val s = scalar(a[0]); if (s is FValue.Err) return s
                    val st = num(scalar(a[1])); if (st is FValue.Err) return st
                    val ln = num(scalar(a[2])); if (ln is FValue.Err) return ln
                    val start = (st as FValue.Num).v.toInt(); val len = (ln as FValue.Num).v.toInt()
                    if (start < 1 || len < 0) return FValue.VALUE
                    val t = text(s)
                    FValue.Str(if (start > t.length) "" else t.substring(start - 1, minOf(t.length, start - 1 + len)))
                }
                "TEXT" -> {
                    val v = scalar(a[0]); if (v is FValue.Err) return v
                    val fmt = scalar(a[1]); if (fmt is FValue.Err) return fmt
                    val code = text(fmt)
                    when {
                        code.isEmpty() -> FValue.Str("")
                        v is FValue.Bool -> FValue.Str(text(v))
                        v is FValue.Blank && code.equals("General", true) -> FValue.Str("")
                        else -> when (val nv = num(v)) {
                            is FValue.Num -> FValue.Str(NumberFormat.format(nv.v, code, ctx.date1904))
                            else -> FValue.Str(NumberFormat.of(code).formatText(text(v)))
                        }
                    }
                }
                "ISBLANK" -> FValue.Bool(scalar(a[0]) is FValue.Blank)
                "ISNUMBER" -> FValue.Bool(scalar(a[0]) is FValue.Num)
                "ISTEXT" -> FValue.Bool(scalar(a[0]) is FValue.Str)
                "ISERROR" -> FValue.Bool(scalar(a[0]) is FValue.Err)
                else -> FValue.NAME
            }
        }

        /** IF branches may return references (e.g. inside SUM); evaluate as scalars here. */
        private fun branch(n: FNode): FValue = scalar(n)

        private inline fun num1(a: List<FNode>, f: (Double) -> Double): FValue =
            num(scalar(a[0])).let { if (it is FValue.Num) FValue.Num(f(it.v)) else it }

        private inline fun str1(a: List<FNode>, f: (String) -> FValue): FValue =
            scalar(a[0]).let { if (it is FValue.Err) it else f(text(it)) }

        /**
         * SUM-like aggregation: numbers in references count, text/booleans/blanks in references
         * are ignored; direct arguments are coerced (text that is not a number is #VALUE!).
         */
        /** True for arguments written as references: their text and booleans are ignored by aggregates. */
        private fun isReference(n: FNode): Boolean = n is FNode.Ref || (n is FNode.Name && ctx.resolveName(n.name) is FNode.Ref)

        /** The values of a reference argument (an area or a single cell). */
        private fun referenced(v: FValue): Sequence<FValue> = if (v is FValue.Area) v.cells() else sequenceOf(v)

        /**
         * SUM-like aggregation: numbers in references count, text/booleans/blanks in references
         * are ignored; direct arguments are coerced (text that is not a number is #VALUE!).
         */
        private inline fun aggregate(args: List<FNode>, f: (List<Double>) -> Double): FValue {
            val nums = ArrayList<Double>()
            for (arg in args) {
                if (arg is FNode.Missing) { nums += 0.0; continue }
                val v = eval(arg)
                if (v is FValue.Area || isReference(arg)) {
                    for (cv in referenced(v)) {
                        when (cv) {
                            is FValue.Num -> nums += cv.v
                            is FValue.Err -> return cv
                            else -> {}
                        }
                    }
                    continue
                }
                when (v) {
                    is FValue.Err -> return v
                    is FValue.Blank -> {}
                    else -> {
                        val n = num(v)
                        if (n is FValue.Err) return n
                        nums += (n as FValue.Num).v
                    }
                }
            }
            return FValue.Num(f(nums))
        }

        private fun count(args: List<FNode>): Int {
            var n = 0
            for (arg in args) {
                val v = eval(arg)
                if (v is FValue.Area || isReference(arg)) {
                    n += referenced(v).count { it is FValue.Num }
                    continue
                }
                when (v) {
                    is FValue.Num, is FValue.Bool -> n++
                    is FValue.Str -> if (parseNumber(v.s) != null) n++
                    else -> {}
                }
            }
            return n
        }

        private fun countA(args: List<FNode>): Int {
            var n = 0
            for (arg in args) {
                if (arg is FNode.Missing) { n++; continue }
                val v = eval(arg)
                if (v is FValue.Area || isReference(arg)) {
                    n += referenced(v).count { it !is FValue.Blank }
                    continue
                }
                n++
            }
            return n
        }

        private fun logical(args: List<FNode>, isAnd: Boolean): FValue {
            var seen = false
            var acc = isAnd
            for (arg in args) {
                val v = eval(arg)
                val ref = v is FValue.Area || isReference(arg)
                val values: Sequence<FValue> = if (ref) referenced(v).filter { it is FValue.Num || it is FValue.Bool || it is FValue.Err } else sequenceOf(v)
                for (x in values) {
                    if (x is FValue.Blank) continue
                    val b = bool(x)
                    if (b is FValue.Err) return b
                    seen = true
                    acc = if (isAnd) acc && (b as FValue.Bool).b else acc || (b as FValue.Bool).b
                }
            }
            return if (!seen) FValue.VALUE else FValue.Bool(acc)
        }

        private fun vlookup(a: List<FNode>): FValue {
            val key = scalar(a[0]); if (key is FValue.Err) return key
            val table = eval(a[1]) as? FValue.Area ?: return FValue.NA
            val ci = num(scalar(a[2])); if (ci is FValue.Err) return ci
            val colIdx = (ci as FValue.Num).v.toInt()
            if (colIdx < 1) return FValue.VALUE
            if (colIdx > table.cols) return FValue.REF
            val approximate = if (a.size >= 4 && a[3] !is FNode.Missing) {
                val b = bool(scalar(a[3])); if (b is FValue.Err) return b
                (b as FValue.Bool).b
            } else true
            val lookup = if (key is FValue.Blank) FValue.Num(0.0) else key
            val lastRow = minOf(table.r2, ctx.lastRowHint(table))
            if (!approximate) {
                val wildcard = (lookup as? FValue.Str)?.s?.let { s -> s.contains('*') || s.contains('?') } == true
                val regex = if (wildcard) wildcardRegex((lookup as FValue.Str).s) else null
                for (r in table.r1..lastRow) {
                    val v = table.get(r, table.c1)
                    val match = when {
                        regex != null -> v is FValue.Str && regex.matches(v.s)
                        else -> sameType(v, lookup) && compare(v, lookup) == 0
                    }
                    if (match) return table.get(r, table.c1 + colIdx - 1).let { if (it is FValue.Blank) FValue.Num(0.0) else it }
                }
                return FValue.NA
            }
            var found = -1
            for (r in table.r1..lastRow) {
                val v = table.get(r, table.c1)
                if (v is FValue.Blank || !sameType(v, lookup)) continue
                if (compare(v, lookup) <= 0) found = r else break
            }
            if (found < 0) return FValue.NA
            return table.get(found, table.c1 + colIdx - 1).let { if (it is FValue.Blank) FValue.Num(0.0) else it }
        }

        private fun sameType(a: FValue, b: FValue): Boolean = when (a) {
            is FValue.Num -> b is FValue.Num
            is FValue.Str -> b is FValue.Str
            is FValue.Bool -> b is FValue.Bool
            else -> false
        }

        private fun wildcardRegex(p: String): Regex {
            val sb = StringBuilder()
            var i = 0
            while (i < p.length) {
                val c = p[i]
                when {
                    c == '~' && i + 1 < p.length -> { sb.append(Regex.escape(p[i + 1].toString())); i++ }
                    c == '*' -> sb.append(".*")
                    c == '?' -> sb.append('.')
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(sb.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        }
    }

    // ----------------------------------------------------------------------------------------
    // Coercions

    fun num(v: FValue): FValue = when (v) {
        is FValue.Num -> v
        is FValue.Bool -> FValue.Num(if (v.b) 1.0 else 0.0)
        is FValue.Blank -> FValue.Num(0.0)
        is FValue.Str -> parseNumber(v.s)?.let { FValue.Num(it) } ?: FValue.VALUE
        is FValue.Err -> v
        is FValue.Area -> FValue.VALUE
    }

    fun bool(v: FValue): FValue = when (v) {
        is FValue.Bool -> v
        is FValue.Num -> FValue.Bool(v.v != 0.0)
        is FValue.Blank -> FValue.Bool(false)
        is FValue.Str -> when (v.s.uppercase()) {
            "TRUE" -> FValue.Bool(true)
            "FALSE" -> FValue.Bool(false)
            else -> FValue.VALUE
        }
        is FValue.Err -> v
        is FValue.Area -> FValue.VALUE
    }

    /** Text form used by `&` and CONCAT: numbers with up to 15 significant digits. */
    fun text(v: FValue): String = when (v) {
        is FValue.Str -> v.s
        is FValue.Num -> numberToText(v.v)
        is FValue.Bool -> if (v.b) "TRUE" else "FALSE"
        is FValue.Blank -> ""
        is FValue.Err -> v.code
        is FValue.Area -> "#VALUE!"
    }

    fun numberToText(d: Double): String {
        if (d == 0.0) return "0"
        if (d.isNaN() || d.isInfinite()) return "#NUM!"
        val bd = BigDecimal(d).round(MathContext(15)).stripTrailingZeros()
        val a = abs(d)
        if (a >= 1e21 || a < 1e-20) {
            val s = String.format(java.util.Locale.ROOT, "%.14E", d)
            val mant = s.substringBefore('E').trimEnd('0').trimEnd('.')
            val exp = s.substringAfter('E').toInt()
            return mant + "E" + (if (exp < 0) "-" else "+") + abs(exp).toString().padStart(2, '0')
        }
        return if (bd.scale() < 0) bd.setScale(0).toPlainString() else bd.toPlainString()
    }

    /** Numbers as typed in text: optional sign, thousands separators, decimals, exponent, percent. */
    fun parseNumber(s: String): Double? {
        val t = s.trim()
        if (t.isEmpty()) return null
        var body = t
        var percent = false
        if (body.endsWith("%")) { percent = true; body = body.dropLast(1).trim() }
        if (!Regex("""^[+-]?(\d{1,3}(,\d{3})+|\d+)?(\.\d*)?([eE][+-]?\d+)?$""").matches(body)) return null
        if (body.none { it.isDigit() }) return null
        val v = body.replace(",", "").toDoubleOrNull() ?: return null
        return if (percent) v / 100.0 else v
    }

    /** Spreadsheet ordering: numbers < text < booleans; text is case-insensitive. */
    fun compare(a0: FValue, b0: FValue): Int {
        var a = a0
        var b = b0
        if (a is FValue.Blank) a = when (b) { is FValue.Str -> FValue.Str(""); is FValue.Bool -> FValue.Bool(false); else -> FValue.Num(0.0) }
        if (b is FValue.Blank) b = when (a) { is FValue.Str -> FValue.Str(""); is FValue.Bool -> FValue.Bool(false); else -> FValue.Num(0.0) }
        fun rank(v: FValue) = when (v) { is FValue.Num -> 0; is FValue.Str -> 1; is FValue.Bool -> 2; else -> 3 }
        val ra = rank(a); val rb = rank(b)
        if (ra != rb) return ra.compareTo(rb)
        return when (a) {
            is FValue.Num -> a.v.compareTo((b as FValue.Num).v)
            is FValue.Str -> a.s.compareTo((b as FValue.Str).s, ignoreCase = true)
            is FValue.Bool -> a.b.compareTo((b as FValue.Bool).b)
            else -> 0
        }
    }

    fun roundTo(x: Double, digits: Int, mode: RoundingMode): Double {
        if (x.isNaN() || x.isInfinite()) return x
        return BigDecimal.valueOf(x).setScale(digits, mode).toDouble()
    }
}

/** Optional hint so lookups over whole columns stop at the last used row. */
fun EvalContext.lastRowHint(area: FValue.Area): Int = (this as? SheetBoundsProvider)?.lastRow(area.sheet) ?: area.r2

interface SheetBoundsProvider {
    fun lastRow(sheet: String?): Int
}
