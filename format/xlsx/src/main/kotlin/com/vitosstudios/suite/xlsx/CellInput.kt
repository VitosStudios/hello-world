package com.vitosstudios.suite.xlsx

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** What a typed cell entry means, the way a spreadsheet interprets it (en-US conventions). */
sealed class CellInput {
    object Blank : CellInput()
    /** [format] is a number format to apply if the cell is still "General". */
    data class Number(val value: Double, val format: String? = null) : CellInput()
    data class Text(val value: String) : CellInput()
    data class Bool(val value: Boolean) : CellInput()
    /** Formula text without the leading "=". */
    data class Formula(val text: String) : CellInput()

    companion object {
        private val CURRENCY = Regex("""^([+-])?\(?\s*([$€£¥])\s*([+-])?([\d,]*\.?\d*)\s*\)?$""")
        private val CURRENCY_SUFFIX = Regex("""^([+-])?([\d,]*\.?\d*)\s*([€£])$""")
        private val ISO_DATE = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2}):(\d{2})(?::(\d{2}))?)?$""")
        private val US_DATE = Regex("""^(\d{1,2})/(\d{1,2})/(\d{2}|\d{4})(?: (\d{1,2}):(\d{2})(?::(\d{2}))?\s*([AaPp][Mm])?)?$""")
        private val TIME = Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([AaPp][Mm])?$""")

        /**
         * Interprets [raw]: "" -> blank, "'..." -> text, "=..." -> formula, TRUE/FALSE, numbers with
         * thousands separators, percentages ("12%"), currency ("$1,234.50"), ISO and US dates and
         * times. Anything else is text.
         */
        fun parse(raw: String, date1904: Boolean = false): CellInput {
            if (raw.isEmpty()) return Blank
            if (raw.startsWith("'")) return Text(raw.substring(1))
            if (raw.startsWith("=") && raw.length > 1) return Formula(raw.substring(1))
            val t = raw.trim()
            if (t.isEmpty()) return Text(raw)
            if (t.equals("TRUE", true)) return Bool(true)
            if (t.equals("FALSE", true)) return Bool(false)
            if (t.endsWith("%")) {
                FormulaEvaluator.parseNumber(t.dropLast(1))?.let { v ->
                    val decimals = t.dropLast(1).substringAfter('.', "").trim().length
                    return Number(v / 100.0, if (decimals == 0) "0%" else "0." + "0".repeat(minOf(decimals, 10)) + "%")
                }
            }
            FormulaEvaluator.parseNumber(t)?.let { v ->
                return Number(v, if (t.contains(',')) (if (t.contains('.')) "#,##0.00" else "#,##0") else null)
            }
            currency(t)?.let { return it }
            date(t, date1904)?.let { return it }
            return Text(raw)
        }

        private fun currency(t: String): Number? {
            val m = CURRENCY.matchEntire(t)
            if (m != null) {
                val digits = m.groupValues[4]
                if (digits.none { it.isDigit() }) return null
                val v = FormulaEvaluator.parseNumber(digits) ?: return null
                val negative = m.groupValues[1] == "-" || m.groupValues[3] == "-" || (t.startsWith("(") && t.endsWith(")"))
                val sym = m.groupValues[2]
                val fmt = "\"$sym\"#,##0" + if (digits.contains('.')) ".00" else ""
                return Number(if (negative) -v else v, fmt)
            }
            val s = CURRENCY_SUFFIX.matchEntire(t) ?: return null
            val v = FormulaEvaluator.parseNumber(s.groupValues[2]) ?: return null
            val fmt = "#,##0" + (if (s.groupValues[2].contains('.')) ".00" else "") + " \"${s.groupValues[3]}\""
            return Number(if (s.groupValues[1] == "-") -v else v, fmt)
        }

        private fun date(t: String, date1904: Boolean): Number? {
            ISO_DATE.matchEntire(t)?.let { m ->
                val d = safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) ?: return null
                val time = timeOf(m.groupValues[4], m.groupValues[5], m.groupValues[6], "") ?: LocalTime.MIDNIGHT
                val hasTime = m.groupValues[4].isNotEmpty()
                return Number(ExcelDates.serial(LocalDateTime.of(d, time), date1904), if (hasTime) "yyyy-mm-dd h:mm" else "yyyy-mm-dd")
            }
            US_DATE.matchEntire(t)?.let { m ->
                var y = m.groupValues[3].toInt()
                if (m.groupValues[3].length == 2) y += if (y < 30) 2000 else 1900
                val d = safeDate(y, m.groupValues[1].toInt(), m.groupValues[2].toInt()) ?: return null
                val hasTime = m.groupValues[4].isNotEmpty()
                val time = if (hasTime) timeOf(m.groupValues[4], m.groupValues[5], m.groupValues[6], m.groupValues[7]) ?: return null else LocalTime.MIDNIGHT
                return Number(ExcelDates.serial(LocalDateTime.of(d, time), date1904), if (hasTime) "m/d/yyyy h:mm" else "m/d/yyyy")
            }
            TIME.matchEntire(t)?.let { m ->
                val time = timeOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]) ?: return null
                val fmt = when {
                    m.groupValues[4].isNotEmpty() -> if (m.groupValues[3].isNotEmpty()) "h:mm:ss AM/PM" else "h:mm AM/PM"
                    m.groupValues[3].isNotEmpty() -> "h:mm:ss"
                    else -> "h:mm"
                }
                return Number(time.toSecondOfDay() / 86400.0, fmt)
            }
            return null
        }

        private fun safeDate(y: Int, m: Int, d: Int): LocalDate? = try { LocalDate.of(y, m, d) } catch (e: Exception) { null }

        private fun timeOf(h: String, mi: String, s: String, ampm: String): LocalTime? {
            if (h.isEmpty()) return null
            var hour = h.toInt()
            val minute = mi.toInt()
            val sec = if (s.isEmpty()) 0 else s.toInt()
            if (ampm.isNotEmpty()) {
                if (hour !in 1..12) return null
                val pm = ampm.startsWith("p", ignoreCase = true)
                hour = (hour % 12) + if (pm) 12 else 0
            }
            if (hour > 23 || minute > 59 || sec > 59) return null
            return LocalTime.of(hour, minute, sec)
        }
    }
}
