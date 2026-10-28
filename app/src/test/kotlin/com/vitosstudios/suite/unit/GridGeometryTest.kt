package com.vitosstudios.suite.unit

import com.vitosstudios.suite.feature.sheets.GridGeometry
import com.vitosstudios.suite.xlsx.XlsxWorkbook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GridGeometryTest {
    @Test
    fun hitTestingMatchesPrefixSums() {
        XlsxWorkbook.createNew().use { wb ->
            val ws = wb.sheet(0)
            ws.setColumnWidth(2, 30.0)
            ws.setRowHeight(3, 40.0)
            ws.freezePanes(1, 1)
            val g = GridGeometry(ws, density = 2f, zoom = 1f, maxRow = 100, maxCol = 20)
            assertTrue(g.x(3) - g.x(2) > g.x(2) - g.x(1), "column B is wider")
            assertTrue(g.y(4) - g.y(3) > g.y(3) - g.y(2), "row 3 is taller")
            for (c in 1..20) assertEquals(c, g.colAt(g.x(c) + 1f))
            for (r in 1..100) assertEquals(r, g.rowAt(g.y(r) + 1f))
            assertEquals(1, g.frozenRows)
            assertEquals(1, g.frozenCols)
        }
    }
}
