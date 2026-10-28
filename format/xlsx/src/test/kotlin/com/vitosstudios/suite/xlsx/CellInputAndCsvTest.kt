package com.vitosstudios.suite.xlsx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CellInputAndCsvTest {
    @Test
    fun parsesTypedInput() {
        assertEquals(CellInput.Blank, CellInput.parse(""))
        assertEquals(CellInput.Number(42.0), CellInput.parse("42"))
        assertEquals(CellInput.Number(-1.5e3), CellInput.parse("-1.5E3"))
        assertEquals(CellInput.Number(1234567.0, "#,##0"), CellInput.parse("1,234,567"))
        assertEquals(CellInput.Number(0.12, "0%"), CellInput.parse("12%"))
        assertEquals(CellInput.Number(-5.0, "\"$\"#,##0"), CellInput.parse("-$5"))
        assertEquals(CellInput.Number(-1234.5, "\"$\"#,##0.00"), CellInput.parse("($1,234.50)"))
        assertEquals(CellInput.Number(10.0, "#,##0 \"€\""), CellInput.parse("10 €"))
        assertEquals(CellInput.Bool(true), CellInput.parse("true"))
        assertEquals(CellInput.Formula("A1+1"), CellInput.parse("=A1+1"))
        assertEquals(CellInput.Text("=A1"), CellInput.parse("'=A1"))
        assertEquals(CellInput.Text("1,2"), CellInput.parse("1,2"))
        assertEquals(CellInput.Text("hello"), CellInput.parse("hello"))
        val d = CellInput.parse("3/15/2024") as CellInput.Number
        assertEquals(45366.0, d.value)
        assertEquals("m/d/yyyy", d.format)
        val t = CellInput.parse("1:30 PM") as CellInput.Number
        assertEquals(13.5 / 24, t.value, 1e-12)
        val dt = CellInput.parse("2024-03-15 06:00") as CellInput.Number
        assertEquals(45366.25, dt.value, 1e-9)
        assertEquals(CellInput.Text("2024-02-30"), CellInput.parse("2024-02-30"), "invalid dates stay text")
    }

    @Test
    fun csvParsing() {
        assertEquals(';', Csv.detectDelimiter("a;b;c\n1;2;3"))
        assertEquals('\t', Csv.detectDelimiter("a\tb\n"))
        assertEquals(',', Csv.detectDelimiter("\"x;y\",z\n"))
        assertEquals(
            listOf(listOf("a", "b c", "d\"e"), listOf("1", "", "line1\r\nline2"), listOf("last")),
            Csv.parse("a,\"b c\",\"d\"\"e\"\r\n1,,\"line1\r\nline2\"\nlast"),
        )
        assertEquals(listOf(listOf("", "")), Csv.parse(","))
        assertEquals(listOf(listOf("x")), Csv.parse("﻿x\n"))
        val rows = listOf(listOf("a,b", "q\"t", " lead"), listOf("plain"))
        assertEquals(rows, Csv.parse(Csv.write(rows)))
    }

    @Test
    fun cellRefs() {
        assertEquals("XFD1048576", CellRef(SheetLimits.MAX_ROWS, SheetLimits.MAX_COLS).a1)
        assertEquals(CellRef(10, 28), CellRef.parse("\$AB\$10"))
        assertEquals(CellRange(1, 2, SheetLimits.MAX_ROWS, 3), CellRange.parse("B:C"))
        assertEquals("\$A\$1:\$B\$4", CellRange.parse("B4:A1").absoluteA1)
        assertTrue(CellRange.parse("A1:C3").intersects(CellRange.parse("C3:D4")))
        assertEquals(2, CellRange.parseList("A1:B2 D4").size)
    }
}
