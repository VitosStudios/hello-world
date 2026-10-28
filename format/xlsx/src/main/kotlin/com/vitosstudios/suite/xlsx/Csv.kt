package com.vitosstudios.suite.xlsx

/** RFC 4180 CSV reading and writing, with delimiter detection (comma, semicolon, tab). */
object Csv {
    fun detectDelimiter(text: String): Char {
        val firstLine = StringBuilder()
        var quoted = false
        for (c in text) {
            if (c == '"') quoted = !quoted
            if (!quoted && (c == '\n' || c == '\r')) break
            firstLine.append(c)
        }
        val counts = listOf(',', ';', '\t').associateWith { d ->
            var n = 0
            var q = false
            for (c in firstLine) { if (c == '"') q = !q else if (!q && c == d) n++ }
            n
        }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    fun parse(text: String, delimiter: Char? = null): List<List<String>> {
        val d = delimiter ?: detectDelimiter(text)
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var i = if (text.startsWith("﻿")) 1 else 0
        var quoted = false
        var fieldStarted = false
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i += 2; continue }
                    quoted = false
                } else field.append(c)
                i++
                continue
            }
            when (c) {
                '"' -> if (field.isEmpty() && !fieldStarted) { quoted = true; fieldStarted = true } else field.append(c)
                d -> { row += field.toString(); field.setLength(0); fieldStarted = false }
                '\r', '\n' -> {
                    row += field.toString(); field.setLength(0); fieldStarted = false
                    rows += row; row = ArrayList()
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> { field.append(c); fieldStarted = true }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty() || fieldStarted) { row += field.toString(); rows += row }
        return rows
    }

    fun write(rows: List<List<String>>, delimiter: Char = ','): String {
        val sb = StringBuilder()
        for (r in rows) {
            r.forEachIndexed { i, f ->
                if (i > 0) sb.append(delimiter)
                val needs = f.any { it == delimiter || it == '"' || it == '\n' || it == '\r' } || f.startsWith(" ") || f.endsWith(" ")
                if (needs) sb.append('"').append(f.replace("\"", "\"\"")).append('"') else sb.append(f)
            }
            sb.append("\r\n")
        }
        return sb.toString()
    }

    /** Fills [ws] from CSV text starting at A1. Values are typed like input, never as formulas. */
    internal fun fill(ws: Worksheet, text: String, delimiter: Char? = null) {
        ws.workbook.edit("Import CSV") {
            for ((r, fields) in parse(text, delimiter).withIndex()) {
                for ((c, f) in fields.withIndex()) {
                    if (f.isEmpty()) continue
                    var input = CellInput.parse(f, ws.workbook.date1904)
                    if (input is CellInput.Formula) input = CellInput.Text(f)
                    ws.setCellContent(r + 1, c + 1, input)
                }
            }
        }
    }

    internal fun export(ws: Worksheet, delimiter: Char, displayed: Boolean): String {
        val used = ws.usedRange() ?: return ""
        val rows = ArrayList<List<String>>()
        for (r in 1..used.bottom) {
            val line = ArrayList<String>()
            for (c in 1..used.right) {
                val v = ws.cell(r, c)
                line += when {
                    v == null -> ""
                    displayed -> v.display
                    v.type == CellType.NUMBER -> FormulaEvaluator.numberToText(v.number!!)
                    v.type == CellType.BOOLEAN -> if (v.value == true) "TRUE" else "FALSE"
                    else -> v.value?.toString() ?: ""
                }
            }
            while (line.isNotEmpty() && line.last().isEmpty()) line.removeAt(line.size - 1)
            rows += line
        }
        return write(rows, delimiter)
    }
}
