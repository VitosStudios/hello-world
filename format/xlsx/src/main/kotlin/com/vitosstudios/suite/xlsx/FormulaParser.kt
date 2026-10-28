package com.vitosstudios.suite.xlsx

/** Formula syntax tree. */
sealed class FNode {
    data class Num(val v: Double) : FNode()
    data class Str(val s: String) : FNode()
    data class Bool(val b: Boolean) : FNode()
    data class Err(val code: String) : FNode()
    data class Ref(val spec: RefSpec) : FNode()
    data class Name(val name: String) : FNode()
    data class Func(val name: String, val args: List<FNode>) : FNode()
    data class Unary(val op: String, val e: FNode) : FNode()
    data class Binary(val op: String, val l: FNode, val r: FNode) : FNode()
    data class Percent(val e: FNode) : FNode()
    object Missing : FNode()
    /** Construct this engine does not evaluate. */
    data class Unsupported(val what: String) : FNode()
}

/** Recursive-descent parser with spreadsheet operator precedence. */
class FormulaParser private constructor(tokens: List<FToken>) {
    // Spaces are insignificant except as the intersection operator, which is unsupported.
    private val toks: List<FToken>
    private var pos = 0

    init {
        val list = ArrayList<FToken>()
        for ((i, t) in tokens.withIndex()) {
            if (t is FToken.Space) {
                val prev = tokens.subList(0, i).lastOrNull { it !is FToken.Space }
                val next = tokens.subList(i + 1, tokens.size).firstOrNull { it !is FToken.Space }
                if (endsOperand(prev) && startsOperand(next)) list += FToken.Unsupported(" ")
                continue
            }
            list += t
        }
        toks = list
    }

    private fun endsOperand(t: FToken?) = t is FToken.Ref || t is FToken.Name || (t is FToken.Punct && t.raw == ")")
    private fun startsOperand(t: FToken?) = t is FToken.Ref || t is FToken.Name || t is FToken.Func || (t is FToken.Punct && t.raw == "(")

    companion object {
        fun parse(formula: String): FNode {
            val text = formula.removePrefix("=")
            val p = FormulaParser(FormulaLexer.tokenize(text))
            if (p.toks.isEmpty()) throw FormulaSyntaxException("Empty formula")
            val node = p.comparison()
            if (p.pos < p.toks.size) throw FormulaSyntaxException("Unexpected '${p.toks[p.pos].raw}'")
            return node
        }
    }

    private fun peek(): FToken? = toks.getOrNull(pos)
    private fun isOp(vararg ops: String): Boolean = (peek() as? FToken.Op)?.raw in ops
    private fun isPunct(p: String): Boolean = (peek() as? FToken.Punct)?.raw == p

    private fun comparison(): FNode {
        var l = concat()
        while (isOp("=", "<>", "<", ">", "<=", ">=")) {
            val op = toks[pos++].raw
            l = FNode.Binary(op, l, concat())
        }
        return l
    }

    private fun concat(): FNode {
        var l = additive()
        while (isOp("&")) { pos++; l = FNode.Binary("&", l, additive()) }
        return l
    }

    private fun additive(): FNode {
        var l = multiplicative()
        while (isOp("+", "-")) {
            val op = toks[pos++].raw
            l = FNode.Binary(op, l, multiplicative())
        }
        return l
    }

    private fun multiplicative(): FNode {
        var l = power()
        while (isOp("*", "/")) {
            val op = toks[pos++].raw
            l = FNode.Binary(op, l, power())
        }
        return l
    }

    private fun power(): FNode {
        var l = unary()
        while (isOp("^")) { pos++; l = FNode.Binary("^", l, unary()) }
        return l
    }

    private fun unary(): FNode {
        if (isOp("-", "+")) {
            val op = toks[pos++].raw
            return FNode.Unary(op, unary())
        }
        return percent()
    }

    private fun percent(): FNode {
        var e = primary()
        while (true) {
            if (isOp("%")) { pos++; e = FNode.Percent(e); continue }
            val t = peek()
            if (t is FToken.Unsupported) {
                // Intersection (" ") or another unsupported operator between operands.
                pos++
                val next = peek()
                if (next != null && next !is FToken.Op && !(next is FToken.Punct && next.raw != "(")) primary()
                e = FNode.Unsupported(t.raw)
                continue
            }
            return e
        }
    }

    private fun primary(): FNode {
        val t = peek() ?: throw FormulaSyntaxException("Unexpected end of formula")
        pos++
        return when (t) {
            is FToken.Number -> FNode.Num(t.value)
            is FToken.Str -> FNode.Str(t.value)
            is FToken.Bool -> FNode.Bool(t.value)
            is FToken.Error -> FNode.Err(t.raw.uppercase())
            is FToken.Ref -> {
                if (isOp(":")) { pos++; primary(); FNode.Unsupported("range operator") } else FNode.Ref(t.spec)
            }
            is FToken.Name -> {
                if (isOp(":")) { pos++; primary(); FNode.Unsupported("range operator") } else FNode.Name(t.raw)
            }
            is FToken.Unsupported -> {
                // Consume a directly attached operand, e.g. "@A1:A3" or "[1]Sheet1!A1".
                val next = peek()
                if (next is FToken.Ref || next is FToken.Name || next is FToken.Func || next is FToken.Unsupported ||
                    (next is FToken.Punct && next.raw == "(")
                ) primary()
                FNode.Unsupported(t.raw)
            }
            is FToken.Func -> {
                if (!isPunct("(")) throw FormulaSyntaxException("Expected '(' after ${t.raw}")
                pos++
                val args = ArrayList<FNode>()
                if (isPunct(")")) {
                    pos++
                } else {
                    while (true) {
                        args += if (isPunct(",") || isPunct(")")) FNode.Missing else comparison()
                        if (isPunct(",")) { pos++; continue }
                        if (isPunct(")")) { pos++; break }
                        throw FormulaSyntaxException("Expected ',' or ')' in ${t.raw}")
                    }
                }
                FNode.Func(t.raw.uppercase(), args)
            }
            is FToken.Punct -> {
                if (t.raw != "(") throw FormulaSyntaxException("Unexpected '${t.raw}'")
                val e = comparison()
                if (!isPunct(")")) throw FormulaSyntaxException("Missing ')'")
                pos++
                e
            }
            is FToken.Op -> throw FormulaSyntaxException("Unexpected '${t.raw}'")
            is FToken.Space -> primary()
        }
    }
}

/** Token-level rewrites that keep every untouched character of the formula. */
object FormulaRewrite {
    /** Shared-formula translation: moves relative references by the offset. */
    fun shift(formula: String, dRow: Int, dCol: Int): String = rewrite(formula) { t ->
        if (t is FToken.Ref) {
            val moved = t.spec.shifted(dRow, dCol)
            if (moved == null) "#REF!" else moved.toA1(sheetPrefix(t))
        } else null
    }

    /** Replaces references to sheet [old] (case-insensitive) with [new]. */
    fun renameSheet(formula: String, old: String, new: String): String = rewrite(formula) { t ->
        if (t is FToken.Ref && (t.spec.sheet.equals(old, true) || t.spec.sheet2.equals(old, true))) {
            val s1 = if (t.spec.sheet.equals(old, true)) new else t.spec.sheet!!
            val s2 = t.spec.sheet2?.let { if (it.equals(old, true)) new else it }
            val prefix = if (s2 != null) quoteSheetPair(s1, s2) else FormulaLexer.quoteSheet(s1)
            t.spec.toA1(prefix)
        } else null
    }

    /** References to a deleted sheet become #REF!. */
    fun invalidateSheet(formula: String, name: String): String = rewrite(formula) { t ->
        if (t is FToken.Ref && (t.spec.sheet.equals(name, true) || t.spec.sheet2.equals(name, true))) "#REF!" else null
    }

    /** True if [formula] references sheet [name]. */
    fun referencesSheet(formula: String, name: String): Boolean = try {
        FormulaLexer.tokenize(formula).any { it is FToken.Ref && (it.spec.sheet.equals(name, true) || it.spec.sheet2.equals(name, true)) }
    } catch (e: FormulaSyntaxException) { false }

    /**
     * Canonical stored form of user input: function names and references upper-cased,
     * functions newer than the 2007 file format prefixed with `_xlfn.` as Excel expects.
     */
    fun normalizeInput(formula: String): String = rewrite(formula.removePrefix("=")) { t ->
        when (t) {
            is FToken.Func -> {
                val u = t.raw.uppercase()
                if (u in FUTURE_FUNCTIONS) "_xlfn.$u" else u
            }
            is FToken.Ref -> t.spec.toA1(sheetPrefix(t))
            is FToken.Bool -> t.raw.uppercase()
            else -> null
        }
    }

    /** Functions stored with the `_xlfn.` prefix. */
    val FUTURE_FUNCTIONS = setOf("CONCAT", "TEXTJOIN", "IFS", "MAXIFS", "MINIFS", "SWITCH", "XLOOKUP", "IFNA")

    private fun quoteSheetPair(a: String, b: String): String {
        val qa = FormulaLexer.quoteSheet(a); val qb = FormulaLexer.quoteSheet(b)
        return if (qa.startsWith("'") || qb.startsWith("'")) "'" + (a + ":" + b).replace("'", "''") + "'" else "$a:$b"
    }

    /** The sheet prefix exactly as written in the token, without the "!". */
    private fun sheetPrefix(t: FToken.Ref): String? {
        if (t.spec.sheet == null) return null
        val bang = t.raw.lastIndexOf('!')
        return if (bang > 0) t.raw.substring(0, bang) else null
    }

    private fun rewrite(formula: String, f: (FToken) -> String?): String {
        val toks = try { FormulaLexer.tokenize(formula) } catch (e: FormulaSyntaxException) { return formula }
        val sb = StringBuilder(formula.length + 8)
        var changed = false
        for (t in toks) {
            val r = f(t)
            if (r != null && r != t.raw) { sb.append(r); changed = true } else sb.append(t.raw)
        }
        return if (changed) sb.toString() else formula
    }
}
