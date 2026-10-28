package com.vitosstudios.suite.xlsx

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/** The built-in number formats (ids 0-49) with their en-US codes. */
object BuiltinFormats {
    private val codes: Map<Int, String> = mapOf(
        0 to "General",
        1 to "0",
        2 to "0.00",
        3 to "#,##0",
        4 to "#,##0.00",
        5 to "\"$\"#,##0_);\\(\"$\"#,##0\\)",
        6 to "\"$\"#,##0_);[Red]\\(\"$\"#,##0\\)",
        7 to "\"$\"#,##0.00_);\\(\"$\"#,##0.00\\)",
        8 to "\"$\"#,##0.00_);[Red]\\(\"$\"#,##0.00\\)",
        9 to "0%",
        10 to "0.00%",
        11 to "0.00E+00",
        12 to "# ?/?",
        13 to "# ??/??",
        14 to "m/d/yyyy",
        15 to "d-mmm-yy",
        16 to "d-mmm",
        17 to "mmm-yy",
        18 to "h:mm AM/PM",
        19 to "h:mm:ss AM/PM",
        20 to "h:mm",
        21 to "h:mm:ss",
        22 to "m/d/yyyy h:mm",
        37 to "#,##0_);(#,##0)",
        38 to "#,##0_);[Red](#,##0)",
        39 to "#,##0.00_);(#,##0.00)",
        40 to "#,##0.00_);[Red](#,##0.00)",
        41 to "_(* #,##0_);_(* \\(#,##0\\);_(* \"-\"_);_(@_)",
        42 to "_(\"$\"* #,##0_);_(\"$\"* \\(#,##0\\);_(\"$\"* \"-\"_);_(@_)",
        43 to "_(* #,##0.00_);_(* \\(#,##0.00\\);_(* \"-\"??_);_(@_)",
        44 to "_(\"$\"* #,##0.00_);_(\"$\"* \\(#,##0.00\\);_(\"$\"* \"-\"??_);_(@_)",
        45 to "mm:ss",
        46 to "[h]:mm:ss",
        47 to "mmss.0",
        48 to "##0.0E+0",
        49 to "@",
    )

    fun code(id: Int): String? = codes[id]

    fun idFor(code: String): Int? = codes.entries.firstOrNull { it.value == code }?.key

    /** Ids 14-22 and 45-47 are dates or times whatever the locale. */
    fun isDateId(id: Int): Boolean = id in 14..22 || id in 45..47
}

/** Conversion between spreadsheet serial numbers and dates (1900 system with its leap-year bug, or 1904). */
object ExcelDates {
    private val BASE_1900_EARLY = LocalDate.of(1899, 12, 31)
    private val BASE_1900 = LocalDate.of(1899, 12, 30)
    private val BASE_1904 = LocalDate.of(1904, 1, 1)

    /** Serial of [date]; for 1900 dates after February 1900 this includes the fictitious 29 Feb 1900. */
    fun serial(date: LocalDate, date1904: Boolean = false): Double {
        if (date1904) return ChronoUnit.DAYS.between(BASE_1904, date).toDouble()
        val days = ChronoUnit.DAYS.between(BASE_1900, date)
        return if (days <= 60) (days - 1).toDouble() else days.toDouble()
    }

    fun serial(dateTime: LocalDateTime, date1904: Boolean = false): Double {
        val d = serial(dateTime.toLocalDate(), date1904)
        val secs = dateTime.toLocalTime().toNanoOfDay() / 1e9
        return d + secs / 86400.0
    }

    /** Calendar parts for a serial. Day 0 of the 1900 system is "1900-01-00"; day 60 is "1900-02-29". */
    data class Parts(
        val year: Int, val month: Int, val day: Int, val dayOfWeek: Int, // 1 = Monday .. 7 = Sunday
        val hour: Int, val minute: Int, val second: Int, val millis: Int,
        /** Whole days and fraction, for elapsed-time formats. */
        val totalMillis: Long,
    )

    /**
     * Splits [serial] after rounding the time of day to [fractionDigits] decimals of a second
     * (0 rounds to whole seconds). Returns null for negative or out-of-range serials.
     */
    fun parts(serial: Double, date1904: Boolean, fractionDigits: Int = 0): Parts? {
        if (serial < 0 || serial > 2_958_466 || serial.isNaN()) return null
        val unit = when (fractionDigits) {
            0 -> 1000L
            1 -> 100L
            2 -> 10L
            else -> 1L
        }
        val totalMs = ((serial * 86_400_000.0) / unit).roundToLong() * unit
        val days = totalMs / 86_400_000L
        val msOfDay = totalMs % 86_400_000L
        val (y, m, d, dow) = when {
            date1904 -> BASE_1904.plusDays(days).let { listOf(it.year, it.monthValue, it.dayOfMonth, it.dayOfWeek.value) }
            days == 0L -> listOf(1900, 1, 0, 6)
            days == 60L -> listOf(1900, 2, 29, 3)
            days < 60L -> BASE_1900_EARLY.plusDays(days).let { listOf(it.year, it.monthValue, it.dayOfMonth, it.dayOfWeek.value) }
            else -> BASE_1900.plusDays(days).let { listOf(it.year, it.monthValue, it.dayOfMonth, it.dayOfWeek.value) }
        }
        return Parts(
            y, m, d, dow,
            (msOfDay / 3_600_000L).toInt(), ((msOfDay / 60_000L) % 60).toInt(), ((msOfDay / 1000L) % 60).toInt(), (msOfDay % 1000L).toInt(),
            totalMs,
        )
    }
}

/**
 * Formats values with spreadsheet number format codes (en-US conventions).
 *
 * Supported: sections (positive;negative;zero;text) and [conditions], quoted and escaped
 * literals, `_x` spacing, `*x` fill (ignored), colours (ignored), currency/locale tags,
 * `0 # ?` placeholders, thousands separators and scaling commas, percent, scientific, fractions,
 * `@` text, `General`, dates and times including elapsed `[h]` and fractional seconds.
 */
class NumberFormat private constructor(val code: String, private val sections: List<Section>) {

    /** True if the first section formats numbers as dates or times. */
    val isDate: Boolean get() = sections.firstOrNull()?.kind == Kind.DATE

    val isText: Boolean get() = sections.size == 1 && sections[0].kind == Kind.TEXT

    fun format(value: Double, date1904: Boolean = false): String {
        if (value.isNaN() || value.isInfinite()) return "#NUM!"
        val numeric = if (sections.size >= 4) sections.take(3) else sections.filter { it.kind != Kind.TEXT }
        val (section, useAbs) = pickSection(value, numeric.ifEmpty { listOf(GENERAL_SECTION) })
        val v = if (useAbs) abs(value) else value
        val negativePrefix = !useAbs && value < 0
        val body = when (section.kind) {
            Kind.DATE -> formatDate(section, v, date1904) ?: return "#".repeat(8)
            Kind.NUMBER -> formatNumber(section, abs(v))
            Kind.GENERAL -> formatGeneralSection(section, abs(v))
            Kind.TEXT, Kind.LITERAL -> literalOnly(section, generalNumber(abs(v)))
        }
        if (negativePrefix && section.kind != Kind.DATE) {
            // Excel prints the minus sign unless the rounded result is zero.
            return if (isAllZero(body)) body else "-$body"
        }
        return body
    }

    fun formatText(text: String): String {
        val textSection = when {
            sections.size >= 4 -> sections[3]
            sections.size == 1 && sections[0].tokens.any { it is Tok.At } -> sections[0]
            else -> sections.firstOrNull { it.tokens.any { t -> t is Tok.At } }
        } ?: return text
        val sb = StringBuilder()
        for (t in textSection.tokens) {
            when (t) {
                is Tok.Literal -> sb.append(t.text)
                is Tok.At -> sb.append(text)
                else -> {}
            }
        }
        return sb.toString()
    }

    private fun isAllZero(s: String): Boolean = s.any { it.isDigit() } && s.none { it in '1'..'9' }

    private fun pickSection(value: Double, list: List<Section>): Pair<Section, Boolean> {
        if (list.any { it.condition != null }) {
            for ((i, s) in list.withIndex()) {
                val c = s.condition
                if (c != null && c.test(value)) return s to (i == 1 && value < 0)
            }
            val fallback = list.firstOrNull { it.condition == null } ?: list.last()
            return fallback to false
        }
        return when {
            list.size == 1 -> list[0] to false
            value > 0 -> list[0] to false
            value < 0 -> list[1] to true
            list.size >= 3 -> list[2] to true
            else -> list[0] to false
        }
    }

    // ------------------------------------------------------------------------------------------
    // Numbers

    private fun formatGeneralSection(section: Section, v: Double): String {
        val sb = StringBuilder()
        for (t in section.tokens) when (t) {
            is Tok.Literal -> sb.append(t.text)
            is Tok.General -> sb.append(generalNumber(v))
            else -> {}
        }
        return sb.toString()
    }

    private fun literalOnly(section: Section, fallback: String): String {
        if (section.tokens.isEmpty()) return "" // ";;;" hides values
        if (section.kind == Kind.TEXT) return fallback
        val sb = StringBuilder()
        for (t in section.tokens) if (t is Tok.Literal) sb.append(t.text)
        return sb.toString()
    }

    private fun formatNumber(section: Section, v0: Double): String {
        val toks = section.tokens
        if (toks.any { it is Tok.Slash } && section.fraction != null) return formatFraction(section, v0)
        var v = v0
        repeat(toks.count { it is Tok.Percent }) { v *= 100.0 }
        repeat(section.scaleCommas) { v /= 1000.0 }
        val expIdx = toks.indexOfFirst { it is Tok.Exp }
        if (expIdx >= 0) return formatScientific(section, v, expIdx)
        return renderFixed(toks, v, section.groupingCommas)
    }

    /** Renders a fixed-point section (no exponent, no fraction) for a non-negative value. */
    private fun renderFixed(toks: List<Tok>, v: Double, grouping: Set<Int>): String {
        val point = toks.indexOfFirst { it is Tok.Point }
        val intSlots = ArrayList<Int>()
        val decSlots = ArrayList<Int>()
        for ((i, t) in toks.withIndex()) if (t is Tok.Digit) { if (point < 0 || i < point) intSlots += i else decSlots += i }
        val decimals = decSlots.size
        // Spreadsheets work with 15 significant digits, so 0.035 (stored as 0.03499999...) rounds up.
        val rounded = BigDecimal(v).round(MathContext(15)).setScale(decimals, RoundingMode.HALF_UP)
        val plain = rounded.toPlainString()
        var intDigits = plain.substringBefore('.')
        if (intDigits == "0") intDigits = ""
        val decDigits = if (decimals > 0) plain.substringAfter('.', "").padEnd(decimals, '0') else ""

        val out = arrayOfNulls<String>(toks.size)
        // Integer part.
        if (intSlots.isNotEmpty()) {
            if (grouping.isNotEmpty()) {
                val padChars = StringBuilder()
                val missing = intSlots.size - intDigits.length
                if (missing > 0) {
                    for (k in 0 until missing) {
                        when ((toks[intSlots[k]] as Tok.Digit).ch) {
                            '0' -> padChars.append('0')
                            '?' -> padChars.append(' ')
                        }
                    }
                }
                val all = padChars.toString() + intDigits
                out[intSlots[0]] = group(all)
                for (k in 1 until intSlots.size) out[intSlots[k]] = ""
            } else {
                val n = intSlots.size
                val d = intDigits
                for (k in 0 until n) {
                    val slot = intSlots[k]
                    val ch = (toks[slot] as Tok.Digit).ch
                    val fromRight = n - 1 - k // 0 for the last slot
                    val digitIndex = d.length - 1 - fromRight
                    out[slot] = when {
                        k == 0 && d.length > n -> d.substring(0, d.length - n + 1)
                        digitIndex >= 0 -> d[digitIndex].toString()
                        ch == '0' -> "0"
                        ch == '?' -> " "
                        else -> ""
                    }
                }
            }
        } else if (intDigits.isNotEmpty() && point >= 0) {
            // No integer placeholders ("#.##" handled above); e.g. ".00" with 12.3 -> "12.30".
            out[point] = intDigits + "."
        }
        // Decimal part: trailing zeros vanish for '#' and become spaces for '?'.
        var keep = decimals
        while (keep > 0) {
            val ch = (toks[decSlots[keep - 1]] as Tok.Digit).ch
            if (decDigits[keep - 1] == '0' && ch != '0') keep-- else break
        }
        for ((k, slot) in decSlots.withIndex()) {
            val ch = (toks[slot] as Tok.Digit).ch
            out[slot] = if (k < keep) decDigits[k].toString() else if (ch == '?') " " else ""
        }
        val sb = StringBuilder()
        for ((i, t) in toks.withIndex()) {
            val o = out[i]
            when {
                o != null -> sb.append(o)
                t is Tok.Literal -> sb.append(t.text)
                t is Tok.Point -> sb.append('.')
                t is Tok.Percent -> sb.append('%')
                t is Tok.Comma -> if (t.literal) sb.append(',')
                t is Tok.General -> sb.append(generalNumber(v))
                else -> {}
            }
        }
        return sb.toString()
    }

    private fun group(s: String): String {
        if (s.length <= 3) return s
        val sb = StringBuilder()
        val first = s.length % 3
        for ((i, c) in s.withIndex()) {
            if (i > 0 && (i - first) % 3 == 0) sb.append(if (s[i - 1] == ' ') ' ' else ',')
            sb.append(c)
        }
        return sb.toString()
    }

    private fun formatScientific(section: Section, v: Double, expIdx: Int): String {
        val toks = section.tokens
        val exp = toks[expIdx] as Tok.Exp
        val mantissaToks = toks.subList(0, expIdx)
        val expDigits = toks.subList(expIdx + 1, toks.size).filterIsInstance<Tok.Digit>()
        val point = mantissaToks.indexOfFirst { it is Tok.Point }
        val intSlots = mantissaToks.withIndex().filter { it.value is Tok.Digit && (point < 0 || it.index < point) }
        val decSlots = mantissaToks.withIndex().count { it.value is Tok.Digit && point >= 0 && it.index > point }
        var e = 0
        var m = v
        if (v != 0.0) {
            val step = if (intSlots.size > 1 && intSlots.any { (it.value as Tok.Digit).ch == '#' }) intSlots.size else 1
            e = floor(log10(v)).toInt()
            e = if (step > 1) Math.floorDiv(e, step) * step else e - (intSlots.size.coerceAtLeast(1) - 1)
            m = v / 10.0.pow(e)
            // Rounding may push the mantissa to the next power of ten.
            val r = BigDecimal.valueOf(m).setScale(decSlots, RoundingMode.HALF_UP).toDouble()
            if (r >= 10.0.pow(intSlots.size.coerceAtLeast(1)) && step == 1) {
                e += 1
                m = v / 10.0.pow(e)
            }
        }
        val mantissa = renderFixed(mantissaToks, m, emptySet())
        val sign = if (e < 0) "-" else if (exp.sign == '+') "+" else ""
        val digits = abs(e).toString().padStart(expDigits.size.coerceAtLeast(1), '0')
        val expFirst = toks.subList(expIdx + 1, toks.size).indexOfFirst { it is Tok.Digit }
        val beforeDigits = StringBuilder()
        val afterDigits = StringBuilder()
        for ((i, t) in toks.subList(expIdx + 1, toks.size).withIndex()) {
            if (t is Tok.Literal) { if (expFirst >= 0 && i < expFirst) beforeDigits.append(t.text) else afterDigits.append(t.text) }
        }
        return mantissa + exp.letter + sign + beforeDigits + digits + afterDigits
    }

    private fun formatFraction(section: Section, v: Double): String {
        val fr = section.fraction!!
        val toks = section.tokens
        val hasWhole = fr.wholeSlots.isNotEmpty()
        var whole = if (hasWhole) floor(v).toLong() else 0L
        val frac = v - whole
        val maxDen = fr.fixedDenominator ?: (10.0.pow(fr.denSlots.size).toInt() - 1).coerceAtLeast(1)
        var num: Long
        var den: Long
        if (fr.fixedDenominator != null) {
            den = fr.fixedDenominator.toLong()
            num = (frac * den).roundToLong()
        } else {
            val best = bestFraction(frac, maxDen)
            num = best.first
            den = best.second
        }
        if (num == den && hasWhole) { whole += 1; num = 0 }
        if (!hasWhole) num += whole * den
        val out = arrayOfNulls<String>(toks.size)
        val dens = fr.denSlots.map { (toks[it] as Tok.Digit).ch }
        val nums = fr.numSlots.map { (toks[it] as Tok.Digit).ch }

        // A zero whole part: each placeholder prints "0", a space ("?") or nothing ("#"); the
        // separator before the numerator stays after a "0", becomes spaces after a "?" (or before
        // a "?" numerator) and disappears otherwise.
        fun renderZeroWhole() {
            val chars = fr.wholeSlots.map { (toks[it] as Tok.Digit).ch }
            fillDigits(toks, fr.wholeSlots, "", out)
            val sepStart = fr.wholeSlots.last() + 1
            val sepEnd = fr.numSlots.first()
            when {
                '0' in chars -> fillDigits(toks, fr.wholeSlots, "0", out, forceZero = true)
                '?' in chars || nums.first() == '?' -> for (i in sepStart until sepEnd) out[i] = " ".repeat(renderedLength(toks[i]))
                else -> for (i in sepStart until sepEnd) out[i] = ""
            }
        }

        if (num == 0L && hasWhole && '0' !in nums) {
            // Whole number only; the fraction area becomes spaces ('?') or disappears.
            fillDigits(toks, fr.wholeSlots, whole.toString(), out, forceZero = true)
            val blank = '?' in nums || '?' in dens || fr.wholeSlots.any { (toks[it] as Tok.Digit).ch == '?' }
            for (i in fr.areaStart..fr.areaEnd) out[i] = if (blank) " ".repeat(renderedLength(toks[i])) else ""
        } else if (num == 0L && hasWhole && whole == 0L) {
            // "0/1" is shown; a "#" whole part disappears, otherwise it shows 0 and its separator.
            val chars = fr.wholeSlots.map { (toks[it] as Tok.Digit).ch }
            if (chars.all { it == '#' }) {
                fillDigits(toks, fr.wholeSlots, "", out)
                for (i in fr.wholeSlots.last() + 1 until fr.numSlots.first()) out[i] = ""
            } else {
                fillDigits(toks, fr.wholeSlots, "0", out, forceZero = true)
            }
            fillDigits(toks, fr.numSlots, "0", out, forceZero = true)
            val zeroPadded = (toks[fr.denSlots.firstOrNull() ?: 0] as? Tok.Digit)?.ch == '0'
            fillDigits(toks, fr.denSlots, "1", out, leftAlign = !zeroPadded)
        } else {
            if (hasWhole) {
                if (whole == 0L) renderZeroWhole() else fillDigits(toks, fr.wholeSlots, whole.toString(), out)
            }
            fillDigits(toks, fr.numSlots, num.toString(), out, forceZero = num == 0L)
            val denText = if (num == 0L) "1" else den.toString()
            // Zero-padded denominators are right-aligned ("004"), others left-aligned ("4 ").
            val zeroPadded = (toks[fr.denSlots.firstOrNull() ?: 0] as? Tok.Digit)?.ch == '0'
            fillDigits(toks, fr.denSlots, denText, out, leftAlign = !zeroPadded)
        }
        val sb = StringBuilder()
        for ((i, t) in toks.withIndex()) {
            val o = out[i]
            when {
                o != null -> sb.append(o)
                t is Tok.Literal -> sb.append(t.text)
                t is Tok.Slash -> sb.append('/')
                t is Tok.Point -> sb.append('.')
                t is Tok.Percent -> sb.append('%')
                else -> {}
            }
        }
        return sb.toString()
    }

    private fun renderedLength(t: Tok): Int = when (t) {
        is Tok.Literal -> t.text.length
        is Tok.Digit, is Tok.Slash -> 1
        else -> 0
    }

    private fun fillDigits(toks: List<Tok>, slots: List<Int>, digits: String, out: Array<String?>, leftAlign: Boolean = false, forceZero: Boolean = false) {
        val n = slots.size
        if (n == 0) return
        if (leftAlign) {
            for ((k, slot) in slots.withIndex()) {
                val ch = (toks[slot] as Tok.Digit).ch
                out[slot] = when {
                    k == n - 1 && digits.length > n -> digits.substring(k)
                    k < digits.length -> digits[k].toString()
                    ch == '0' -> "0"
                    ch == '?' -> " "
                    else -> ""
                }
            }
            return
        }
        for (k in 0 until n) {
            val slot = slots[k]
            val ch = (toks[slot] as Tok.Digit).ch
            val fromRight = n - 1 - k
            val digitIndex = digits.length - 1 - fromRight
            out[slot] = when {
                k == 0 && digits.length > n -> digits.substring(0, digits.length - n + 1)
                digitIndex >= 0 -> digits[digitIndex].toString()
                ch == '0' || (forceZero && k == n - 1) -> "0"
                ch == '?' -> " "
                else -> ""
            }
        }
    }

    private fun bestFraction(x: Double, maxDen: Int): Pair<Long, Long> {
        var bestN = 0L
        var bestD = 1L
        var bestErr = abs(x)
        for (d in 1..maxDen) {
            val n = (x * d).roundToLong()
            val err = abs(x - n.toDouble() / d)
            if (err < bestErr - 1e-12) {
                bestErr = err; bestN = n; bestD = d.toLong()
                if (err < 1e-12) break
            }
        }
        return bestN to bestD
    }

    // ------------------------------------------------------------------------------------------
    // Dates

    private fun formatDate(section: Section, v: Double, date1904: Boolean): String? {
        val toks = section.tokens
        val fracDigits = toks.filterIsInstance<Tok.SubSecond>().maxOfOrNull { it.digits } ?: 0
        val p = ExcelDates.parts(v, date1904, fracDigits) ?: return null
        val ampm = toks.any { it is Tok.AmPm }
        val sb = StringBuilder()
        for (t in toks) {
            when (t) {
                is Tok.Literal -> sb.append(t.text)
                is Tok.Comma -> sb.append(',')
                is Tok.Point -> sb.append('.')
                is Tok.Percent -> sb.append('%')
                is Tok.Slash -> sb.append('/')
                is Tok.Digit -> sb.append(t.ch)
                is Tok.Date -> when (t.kind) {
                    'y' -> sb.append(if (t.len <= 2) (p.year % 100).toString().padStart(2, '0') else p.year.toString())
                    'M' -> sb.append(
                        when (t.len) {
                            1 -> p.month.toString()
                            2 -> p.month.toString().padStart(2, '0')
                            3 -> MONTHS[p.month - 1].substring(0, 3)
                            5 -> MONTHS[p.month - 1].substring(0, 1)
                            else -> MONTHS[p.month - 1]
                        },
                    )
                    'd' -> sb.append(
                        when (t.len) {
                            1 -> p.day.toString()
                            2 -> p.day.toString().padStart(2, '0')
                            3 -> DAYS[p.dayOfWeek - 1].substring(0, 3)
                            else -> DAYS[p.dayOfWeek - 1]
                        },
                    )
                    'h' -> {
                        val h = if (ampm) (if (p.hour % 12 == 0) 12 else p.hour % 12) else p.hour
                        sb.append(if (t.len >= 2) h.toString().padStart(2, '0') else h.toString())
                    }
                    'm' -> sb.append(if (t.len >= 2) p.minute.toString().padStart(2, '0') else p.minute.toString())
                    's' -> sb.append(if (t.len >= 2) p.second.toString().padStart(2, '0') else p.second.toString())
                }
                is Tok.Elapsed -> {
                    val total = when (t.kind) {
                        'h' -> p.totalMillis / 3_600_000L
                        'm' -> p.totalMillis / 60_000L
                        else -> p.totalMillis / 1000L
                    }
                    sb.append(total.toString().padStart(t.len, '0'))
                }
                is Tok.SubSecond -> {
                    val ms = p.millis.toString().padStart(3, '0')
                    sb.append('.').append(ms.substring(0, t.digits.coerceAtMost(3)))
                }
                is Tok.AmPm -> {
                    val pm = p.hour >= 12
                    sb.append(
                        if (t.short) {
                            val letter = if (pm) 'p' else 'a'
                            if (t.upper) letter.uppercaseChar() else letter
                        } else if (pm) "PM" else "AM",
                    )
                }
                else -> {}
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------------------------------
    // Parsing

    private enum class Kind { NUMBER, DATE, TEXT, GENERAL, LITERAL }

    private class Condition(val op: String, val operand: Double) {
        fun test(v: Double): Boolean = when (op) {
            "<" -> v < operand
            "<=" -> v <= operand
            ">" -> v > operand
            ">=" -> v >= operand
            "=" -> v == operand
            "<>" -> v != operand
            else -> false
        }
    }

    private class Fraction(
        val wholeSlots: List<Int>, val numSlots: List<Int>, val denSlots: List<Int>, val fixedDenominator: Int?,
        val areaStart: Int, val areaEnd: Int,
    )

    private class Section(val tokens: List<Tok>, val condition: Condition?) {
        val kind: Kind = when {
            tokens.any { it is Tok.Date || it is Tok.Elapsed || it is Tok.AmPm } -> Kind.DATE
            tokens.any { it is Tok.Digit } -> Kind.NUMBER
            tokens.any { it is Tok.General } -> Kind.GENERAL
            tokens.any { it is Tok.At } -> Kind.TEXT
            else -> Kind.LITERAL
        }
        var scaleCommas = 0
        val groupingCommas = HashSet<Int>()
        var fraction: Fraction? = null

        init {
            if (kind == Kind.NUMBER) classifyCommas()
            if (kind == Kind.NUMBER) fraction = detectFraction()
        }

        private fun classifyCommas() {
            val point = tokens.indexOfFirst { it is Tok.Point }.let { if (it < 0) tokens.size else it }
            for ((i, t) in tokens.withIndex()) {
                if (t !is Tok.Comma) continue
                val digitBefore = (0 until i).any { tokens[it] is Tok.Digit }
                val digitAfterInInt = (i + 1 until point).any { tokens[it] is Tok.Digit }
                val anyDigitAfter = (i + 1 until tokens.size).any { tokens[it] is Tok.Digit }
                when {
                    digitBefore && digitAfterInInt && i < point -> groupingCommas += i
                    digitBefore && (i < point && !digitAfterInInt || i > point && !anyDigitAfter) -> scaleCommas++
                    else -> t.literal = true
                }
            }
        }

        private fun detectFraction(): Fraction? {
            val slash = tokens.indexOfFirst { it is Tok.Slash }
            if (slash < 0) return null
            // Denominator: digits (or a literal number) right after the slash.
            val denSlots = ArrayList<Int>()
            var fixed: Int? = null
            var j = slash + 1
            var areaEnd = slash
            if (j < tokens.size && tokens[j] is Tok.Literal && (tokens[j] as Tok.Literal).text.all { it.isDigit() } && (tokens[j] as Tok.Literal).text.isNotEmpty()) {
                fixed = (tokens[j] as Tok.Literal).text.toInt()
                areaEnd = j
            } else {
                // Allow literals between slash and the denominator digits ("=/=").
                while (j < tokens.size && tokens[j] !is Tok.Digit) j++
                while (j < tokens.size && tokens[j] is Tok.Digit) { denSlots += j; areaEnd = j; j++ }
            }
            // Numerator: the contiguous digit run before the slash (literals allowed in between).
            var k = slash - 1
            while (k >= 0 && tokens[k] !is Tok.Digit) k--
            val numSlots = ArrayList<Int>()
            while (k >= 0 && tokens[k] is Tok.Digit) { numSlots.add(0, k); k-- }
            if (numSlots.isEmpty() || (denSlots.isEmpty() && fixed == null)) return null
            val wholeSlots = (0..k).filter { tokens[it] is Tok.Digit }
            // The fraction area starts after the whole part's last digit.
            val areaStart = if (wholeSlots.isEmpty()) numSlots.first() else wholeSlots.last() + 1
            return Fraction(wholeSlots, numSlots, denSlots, fixed, areaStart, areaEnd)
        }
    }

    private sealed class Tok {
        class Literal(val text: String) : Tok()
        class Digit(val ch: Char) : Tok()
        object Point : Tok()
        class Comma : Tok() { var literal = false }
        object Percent : Tok()
        class Exp(val letter: Char, val sign: Char) : Tok()
        object Slash : Tok()
        object At : Tok()
        object General : Tok()
        /** kind: y, M (month, resolved), m (minute, resolved), d, h, s. */
        class Date(var kind: Char, val len: Int) : Tok()
        class Elapsed(val kind: Char, val len: Int) : Tok()
        class SubSecond(val digits: Int) : Tok()
        class AmPm(val short: Boolean, val upper: Boolean) : Tok()
    }

    companion object {
        private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        private val GENERAL_SECTION = Section(listOf(Tok.General), null)

        private val cache = object : LinkedHashMap<String, NumberFormat>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NumberFormat>?): Boolean = size > 512
        }

        fun of(code: String): NumberFormat = synchronized(cache) { cache.getOrPut(code) { parse(code) } }

        /** Formats a number (or date serial) with [code]. */
        fun format(value: Double, code: String, date1904: Boolean = false): String = of(code).format(value, date1904)

        fun isDateFormat(code: String): Boolean = of(code).isDate

        /** The "General" rendering: up to 11 characters, scientific for very large/small values. */
        fun generalNumber(v: Double): String {
            if (v == 0.0) return "0"
            if (v.isNaN() || v.isInfinite()) return "#NUM!"
            val a = abs(v)
            val neg = v < 0
            if (a >= 1e11 || a < 1e-9) return (if (neg) "-" else "") + generalScientific(a)
            val intDigits = if (a >= 1) floor(log10(a)).toInt() + 1 else 1
            val decimals = (10 - intDigits).coerceAtLeast(0)
            var bd = BigDecimal(a).round(MathContext(17)).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros()
            if (bd.compareTo(BigDecimal.ZERO) == 0) return "0"
            if (bd.precision() - bd.scale() > 11) return (if (neg) "-" else "") + generalScientific(a)
            if (bd.scale() < 0) bd = bd.setScale(0)
            return (if (neg) "-" else "") + bd.toPlainString()
        }

        private fun generalScientific(a: Double): String {
            var e = floor(log10(a)).toInt()
            var m = BigDecimal(a / 10.0.pow(e)).setScale(5, RoundingMode.HALF_UP)
            if (m >= BigDecimal.TEN) { e += 1; m = BigDecimal(a / 10.0.pow(e)).setScale(5, RoundingMode.HALF_UP) }
            val ms = m.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
            val es = abs(e).toString().padStart(2, '0')
            return ms + "E" + (if (e < 0) "-" else "+") + es
        }

        private fun parse(code: String): NumberFormat {
            if (code.isBlank()) return NumberFormat(code, listOf(GENERAL_SECTION))
            val sections = splitSections(code).map { parseSection(it) }
            return NumberFormat(code, sections.ifEmpty { listOf(GENERAL_SECTION) })
        }

        private fun splitSections(code: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var i = 0
            var quoted = false
            while (i < code.length) {
                val c = code[i]
                when {
                    quoted -> { sb.append(c); if (c == '"') quoted = false }
                    c == '"' -> { quoted = true; sb.append(c) }
                    c == '\\' && i + 1 < code.length -> { sb.append(c).append(code[i + 1]); i++ }
                    c == '[' -> {
                        val end = code.indexOf(']', i)
                        if (end < 0) sb.append(c) else { sb.append(code, i, end + 1); i = end }
                    }
                    c == ';' -> { out += sb.toString(); sb.setLength(0) }
                    else -> sb.append(c)
                }
                i++
            }
            out += sb.toString()
            return out
        }

        private val CONDITION = Regex("""^\[(<=|>=|<>|<|>|=)\s*(-?[\d.]+(?:[eE][+-]?\d+)?)]""")

        private fun parseSection(s: String): Section {
            val toks = ArrayList<Tok>()
            var condition: Condition? = null
            var i = 0
            val lower = s.lowercase()
            fun lit(text: String) {
                val last = toks.lastOrNull()
                if (last is Tok.Literal) toks[toks.size - 1] = Tok.Literal(last.text + text) else toks += Tok.Literal(text)
            }
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> {
                        val end = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }
                        lit(s.substring(i + 1, end)); i = end + 1; continue
                    }
                    c == '\\' -> { if (i + 1 < s.length) lit(s[i + 1].toString()); i += 2; continue }
                    c == '_' -> { lit(" "); i += 2; continue }
                    c == '*' -> { i += 2; continue }
                    c == '[' -> {
                        val end = s.indexOf(']', i)
                        if (end < 0) { lit("["); i++; continue }
                        val inner = s.substring(i + 1, end)
                        val il = inner.lowercase()
                        val cond = CONDITION.find(s.substring(i))
                        when {
                            cond != null && cond.range.first == 0 -> condition = Condition(cond.groupValues[1], cond.groupValues[2].toDouble())
                            il.isNotEmpty() && il.all { it == 'h' } -> toks += Tok.Elapsed('h', il.length)
                            il.isNotEmpty() && il.all { it == 'm' } -> toks += Tok.Elapsed('m', il.length)
                            il.isNotEmpty() && il.all { it == 's' } -> toks += Tok.Elapsed('s', il.length)
                            inner.startsWith("$") -> {
                                val sym = inner.substring(1).substringBefore('-')
                                if (sym.isNotEmpty()) lit(sym)
                            }
                            else -> {} // colours, locale and calendar tags
                        }
                        i = end + 1; continue
                    }
                    lower.startsWith("general", i) -> { toks += Tok.General; i += 7; continue }
                    lower.startsWith("am/pm", i) -> { toks += Tok.AmPm(short = false, upper = true); i += 5; continue }
                    lower.startsWith("a/p", i) -> { toks += Tok.AmPm(short = true, upper = c == 'A'); i += 3; continue }
                    c == '0' || c == '#' || c == '?' -> {
                        // In date sections ".0" after seconds is a fraction of a second.
                        val prev = toks.lastOrNull()
                        if (c == '0' && prev === Tok.Point && toks.any { it is Tok.Date || it is Tok.Elapsed }) {
                            toks.removeAt(toks.size - 1)
                            var n = 0
                            while (i < s.length && s[i] == '0') { n++; i++ }
                            toks += Tok.SubSecond(n)
                            continue
                        }
                        toks += Tok.Digit(c)
                    }
                    c == '.' -> toks += Tok.Point
                    c == ',' -> toks += Tok.Comma()
                    c == '%' -> toks += Tok.Percent
                    c == '/' -> {
                        if (toks.any { it is Tok.Digit }) toks += Tok.Slash else lit("/")
                    }
                    c == '@' -> toks += Tok.At
                    (c == 'E' || c == 'e') && i + 1 < s.length && (s[i + 1] == '+' || s[i + 1] == '-') && toks.any { it is Tok.Digit } -> {
                        toks += Tok.Exp(c, s[i + 1]); i += 2; continue
                    }
                    c.lowercaseChar() in "ymdhs" && !(c.lowercaseChar() == 'e') -> {
                        val k = c.lowercaseChar()
                        var n = 0
                        while (i < s.length && s[i].lowercaseChar() == k) { n++; i++ }
                        toks += Tok.Date(if (k == 'm') 'M' else k, n)
                        continue
                    }
                    else -> lit(c.toString())
                }
                i++
            }
            resolveMinutes(toks)
            return Section(toks, condition)
        }

        /** An "m" token means minutes when it follows an hour or precedes a second. */
        private fun resolveMinutes(toks: List<Tok>) {
            val dateToks = toks.filter { it is Tok.Date || it is Tok.Elapsed }
            for ((idx, t) in dateToks.withIndex()) {
                if (t !is Tok.Date || t.kind != 'M') continue
                val prev = dateToks.getOrNull(idx - 1)
                val next = dateToks.getOrNull(idx + 1)
                val afterHour = (prev is Tok.Date && prev.kind == 'h') || (prev is Tok.Elapsed && prev.kind == 'h')
                val beforeSecond = (next is Tok.Date && next.kind == 's') || (next is Tok.Elapsed && next.kind == 's')
                if ((afterHour || beforeSecond) && t.len <= 2) t.kind = 'm'
            }
        }
    }
}
