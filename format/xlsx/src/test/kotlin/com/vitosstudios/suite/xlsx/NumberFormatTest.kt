package com.vitosstudios.suite.xlsx

import com.vitosstudios.suite.testing.Corpus
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class NumberFormatTest {
    private fun f(v: Double, code: String) = NumberFormat.format(v, code)

    @Test
    fun basics() {
        assertEquals("1234.5", f(1234.5, "General"))
        assertEquals("1,234.50", f(1234.5, "#,##0.00"))
        assertEquals("-1,234.50", f(-1234.5, "#,##0.00"))
        assertEquals("(1,234.50)", f(-1234.5, "#,##0.00;(#,##0.00)"))
        assertEquals("zero", f(0.0, "0;-0;\"zero\""))
        assertEquals("12%", f(0.123, "0%"))
        assertEquals("12.30%", f(0.123, "0.00%"))
        assertEquals("1.23E+03", f(1234.0, "0.00E+00"))
        assertEquals("$1,234.50", f(1234.5, "\"$\"#,##0.00"))
        assertEquals("€5.00", f(5.0, "[$€-407]#,##0.00"))
        assertEquals(" 1/2", f(0.5, "# ?/?"))
        assertEquals("1 1/4", f(1.25, "# ?/?"))
        assertEquals("", f(5.0, ";;;"))
        assertEquals("12", f(12.0, "[Red]0"))
        assertEquals("big", f(500.0, "[>100]\"big\";\"small\""))
        assertEquals("small", f(5.0, "[>100]\"big\";\"small\""))
    }

    @Test
    fun dates() {
        val serial = ExcelDates.serial(LocalDate.of(2024, 3, 15))
        assertEquals(45366.0, serial)
        assertEquals("2024-03-15", f(serial, "yyyy-mm-dd"))
        assertEquals("3/15/2024", f(serial, "m/d/yyyy"))
        assertEquals("15-Mar-24", f(serial, "d-mmm-yy"))
        assertEquals("Friday, March 15, 2024", f(serial, "dddd, mmmm d, yyyy"))
        val dt = ExcelDates.serial(LocalDateTime.of(2024, 3, 15, 13, 5, 9))
        assertEquals("1:05 PM", f(dt, "h:mm AM/PM"))
        assertEquals("13:05:09", f(dt, "hh:mm:ss"))
        assertEquals("49:30:00", f(49.5 / 24, "[h]:mm:ss"))
        assertEquals("1900-02-29", f(60.0, "yyyy-mm-dd"))
        assertEquals("1900-03-01", f(61.0, "yyyy-mm-dd"))
        assertEquals("1904-01-02", NumberFormat.format(1.0, "yyyy-mm-dd", date1904 = true))
        assertTrue(NumberFormat.isDateFormat("yyyy-mm-dd"))
        assertTrue(!NumberFormat.isDateFormat("0.00"))
    }

    @Test
    fun general() {
        assertEquals("1.234567891", NumberFormat.generalNumber(1.2345678912))
        assertEquals("1E+11", NumberFormat.generalNumber(1e11))
        assertEquals("1.12346E+11", NumberFormat.generalNumber(112345900000.0))
        assertEquals("0.000000001", NumberFormat.generalNumber(1e-9))
        assertEquals("1.12346E-10", NumberFormat.generalNumber(1.123459e-10))
        assertEquals("-12345", NumberFormat.generalNumber(-12345.0))
        assertEquals("0.1", NumberFormat.generalNumber(0.1))
        assertEquals("0.3", NumberFormat.generalNumber(0.1 + 0.2))
    }

    /**
     * The POI test workbooks hold, on their "Tests" sheet, the result of Excel's TEXT(value, format)
     * as cached values (column A), the format (B) and the value (C).
     */
    private var date1904 = false

    private fun corpusCases(file: String): List<Triple<String, String, Any?>> {
        XlsxWorkbook.open(Corpus.file("xlsx/$file")).use { wb ->
            date1904 = wb.date1904
            val ws = wb.sheet("Tests") ?: fail("No Tests sheet in $file")
            val out = ArrayList<Triple<String, String, Any?>>()
            for (r in ws.rowNumbers().drop(1)) {
                val expected = ws.cell(r, 1) ?: continue
                val format = ws.cell(r, 2)?.text ?: continue
                val value = ws.cell(r, 3)?.value ?: continue
                if (expected.type != CellType.STRING) continue
                out += Triple(expected.text!!, format, value)
            }
            return out
        }
    }

    private fun check(file: String, minPassRate: Double, mustPass: (String) -> Boolean) {
        val cases = corpusCases(file)
        assertTrue(cases.size > 40, "Too few cases in $file")
        val failures = ArrayList<String>()
        var mandatoryFailures = 0
        for ((expected, code, value) in cases) {
            val actual = when (value) {
                is Double -> NumberFormat.format(value, code, date1904)
                is Boolean -> if (value) "TRUE" else "FALSE"
                else -> NumberFormat.of(code).formatText(value.toString())
            }
            if (actual != expected) {
                failures += "'$code' $value: expected '$expected' got '$actual'"
                if (mustPass(code)) mandatoryFailures++
            }
        }
        val rate = 1.0 - failures.size.toDouble() / cases.size
        println("$file: ${cases.size - failures.size}/${cases.size} pass")
        if (mandatoryFailures > 0 || rate < minPassRate) fail("$file: ${failures.size} failures (pass rate $rate):\n" + failures.joinToString("\n"))
    }

    @Test
    fun excelGeneralFormatResults() = check("excel-poi-GeneralFormatTests.xlsx", 1.0) { true }

    @Test
    fun excelDateFormatResults() = check("excel-poi-DateFormatTests.xlsx", 1.0) { true }

    @Test
    fun excelNumberFormatResults() = check("excel-poi-NumberFormatTests.xlsx", 1.0) { true }
}
