package ch.vitosstudios.tippli.core

import ch.vitosstudios.tippli.core.data.Metrics
import ch.vitosstudios.tippli.core.geometry.Key
import ch.vitosstudios.tippli.core.geometry.KeyArea
import ch.vitosstudios.tippli.core.geometry.KeyShape
import ch.vitosstudios.tippli.core.geometry.KeyType
import ch.vitosstudios.tippli.core.geometry.KeyboardLayout
import ch.vitosstudios.tippli.core.geometry.LayoutBuilder
import ch.vitosstudios.tippli.core.geometry.LayoutRequest
import ch.vitosstudios.tippli.core.geometry.Page
import ch.vitosstudios.tippli.core.geometry.PixelMetrics
import ch.vitosstudios.tippli.core.geometry.Point
import ch.vitosstudios.tippli.core.settings.KeyboardMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeometryTest {
    private val shared = TestShared.data
    private val density = 2.75f
    private val width = 1080f
    private val metrics = PixelMetrics.from(shared.theme.metrics, "medium", density)

    private fun layout(
        keyboardId: String,
        mode: KeyboardMode,
        page: Page = Page.LETTERS,
        numberRow: Boolean = false,
        m: PixelMetrics = metrics,
    ): KeyboardLayout = LayoutBuilder.build(
        LayoutRequest(width, m, shared.layouts, shared.keyboard(keyboardId)!!, mode, page, numberRow),
    )

    private val allCombinations = shared.layouts.keyboards.keys.flatMap { id ->
        listOf(KeyboardMode.HEX, KeyboardMode.NORMAL).flatMap { mode ->
            Page.entries.flatMap { page -> listOf(false, true).map { nr -> layout(id, mode, page, nr) } }
        }
    }

    @Test
    fun everyKeyCentreHitsItself() {
        for (l in allCombinations) {
            for (key in l.keys) {
                assertSame("${l.page} ${l.geometryMode} ${key.code}", key, l.hitTest(key.centerX, key.centerY))
            }
        }
    }

    @Test
    fun totalHeightFollowsSpec() {
        val h = metrics.rowHeight
        val hex = layout("qwertz", KeyboardMode.HEX)
        assertEquals(metrics.toolbarHeight + 4 * h + h / 3 + metrics.bottomPadding, hex.height, 0.01f)
        val normal = layout("qwertz", KeyboardMode.NORMAL)
        assertEquals(metrics.toolbarHeight + 4 * h + metrics.bottomPadding, normal.height, 0.01f)
        val numbers = layout("qwertz", KeyboardMode.NORMAL, numberRow = true)
        assertEquals(metrics.toolbarHeight + 5 * h + metrics.bottomPadding, numbers.height, 0.01f)
        // Number row only in normal mode; symbol pages keep the height of the letters page.
        assertTrue(numbers.keys.any { it.area == KeyArea.NUMBER_ROW })
        assertTrue(layout("qwertz", KeyboardMode.HEX, numberRow = true).keys.none { it.area == KeyArea.NUMBER_ROW })
        assertEquals(hex.height, layout("qwertz", KeyboardMode.HEX, Page.SYMBOLS1).height, 0.01f)
        assertEquals(numbers.height, layout("qwertz", KeyboardMode.NORMAL, Page.SYMBOLS2, numberRow = true).height, 0.01f)
    }

    @Test
    fun symbolRowsShareTheLettersPageHeightEvenly() {
        val h = metrics.rowHeight
        for ((mode, numberRow, expectedRow) in listOf(
            Triple(KeyboardMode.HEX, false, (3 * h + h / 3) / 3),
            Triple(KeyboardMode.NORMAL, true, 4 * h / 3),
            Triple(KeyboardMode.NORMAL, false, h),
        )) {
            for (page in listOf(Page.SYMBOLS1, Page.SYMBOLS2)) {
                val l = layout("qwertz", mode, page, numberRow)
                assertEquals(layout("qwertz", mode, Page.LETTERS, numberRow).height, l.height, 0.01f)
                assertEquals(expectedRow, l.normalRowHeight, 0.01f)
                val rows = l.keys.filter { it.area == KeyArea.LETTERS }.groupBy { it.row }.toSortedMap().values
                assertEquals(3, rows.size)
                rows.forEachIndexed { r, keys ->
                    assertEquals(l.toolbarHeight + r * expectedRow, keys.first().cell.top, 0.01f)
                    assertEquals(expectedRow, keys.first().cell.height, 0.01f)
                }
                // No gap between the last symbol row and the bottom row.
                assertEquals(l.bottomRowTop, rows.last().first().cell.bottom, 0.01f)
                assertEquals(h, l.keys.first { it.area == KeyArea.BOTTOM }.cell.height, 0.01f)
            }
        }
    }

    @Test
    fun normalRowsUseUnitsAndBottomRowFillsWidth() {
        val l = layout("qwertz", KeyboardMode.NORMAL, numberRow = true)
        val u = (width - 2 * metrics.sidePadding) / 11f
        assertEquals(u, l.unit, 0.001f)
        val y = l.keys.first { it.code == "y" }
        // Third row: indent 0.5 + shift 1.5 → y starts at sp + 2u.
        assertEquals(metrics.sidePadding + 2 * u, y.cell.left, 0.01f)
        for (area in listOf(KeyArea.BOTTOM, KeyArea.NUMBER_ROW)) {
            val row = l.keys.filter { it.area == area }
            assertEquals(metrics.sidePadding, row.first().cell.left, 0.01f)
            assertEquals(width - metrics.sidePadding, row.last().cell.right, 0.01f)
        }
        val space = l.keys.first { it.type == KeyType.SPACE }
        val total = shared.layouts.bottomRows.getValue("normal").sumOf { it.width }.toFloat()
        assertEquals(4f / total * (width - 2 * metrics.sidePadding), space.cell.width, 0.01f)
        // Visual rect = cell inset by keyGap/2.
        assertEquals(space.cell.left + metrics.keyGap / 2, space.visual.left, 0.01f)
    }

    @Test
    fun hexKeysFollowFormula() {
        val l = layout("qwertz", KeyboardMode.HEX)
        val u = (width - 2 * metrics.sidePadding) / 11.5f
        val h = metrics.rowHeight
        val a = l.keys.first { it.code == "a" }
        assertEquals(KeyShape.HEX, a.shape)
        assertEquals(metrics.sidePadding + (0.5f + 0 + 0.5f) * u, a.centerX, 0.01f)
        assertEquals(metrics.toolbarHeight + h / 6 + h + h / 2, a.centerY, 0.01f)
        val halfWidth = u / 2 - metrics.hexGap / 2
        val fullHeight = 4 * h / 3 - metrics.hexGap
        assertEquals(Point(a.centerX, a.centerY - fullHeight / 2), a.hexVertices[0])
        assertEquals(a.centerX + halfWidth, a.hexVertices[1].x, 0.001f)
        assertEquals(a.centerY - fullHeight / 4, a.hexVertices[1].y, 0.001f)
        // Two separate space keys; only the first carries the label.
        val spaces = l.keys.filter { it.type == KeyType.SPACE && it.area == KeyArea.LETTERS }
        assertEquals(2, spaces.size)
        assertTrue(spaces[0].primarySpace)
        assertTrue(!spaces[1].primarySpace)
        // Bottom row of hex mode is rectangular and contains backspace.
        assertTrue(l.keys.filter { it.area == KeyArea.BOTTOM }.all { it.shape == KeyShape.RECT })
        assertNotNull(l.keys.firstOrNull { it.area == KeyArea.BOTTOM && it.type == KeyType.BACK })
        // Symbol pages are always rectangular.
        assertTrue(layout("qwertz", KeyboardMode.HEX, Page.SYMBOLS1).keys.all { it.shape == KeyShape.RECT })
    }

    @Test
    fun hexRowsTessellate() {
        // Without gaps adjacent hexagons share their vertices exactly.
        val noGap = PixelMetrics.from(Metrics(hexGap = 0f), "medium", density)
        for (id in shared.layouts.keyboards.keys) {
            val l = layout(id, KeyboardMode.HEX, m = noGap)
            val hex = l.keys.filter { it.shape == KeyShape.HEX }
            val vertices = hex.flatMap { it.hexVertices }
            fun sharedCount(p: Point) = vertices.count { abs(it.x - p.x) < 0.01f && abs(it.y - p.y) < 0.01f }
            for (row in 0 until 2) {
                val upper = hex.filter { it.row == row }
                val lower = hex.filter { it.row == row + 1 }
                for (k in lower) {
                    // The top vertex of a lower-row hexagon touches the upper row (unless it is at the edge).
                    val top = k.hexVertices[0]
                    val touching = upper.filter { u -> abs(u.hexVertices[2].y - top.y) < 0.01f &&
                        (abs(u.hexVertices[2].x - top.x) < 0.01f || abs(u.hexVertices[4].x - top.x) < 0.01f) }
                    if (top.x > upper.first().centerX && top.x < upper.last().centerX) {
                        assertTrue("$id ${k.code} touches the row above", touching.isNotEmpty())
                        assertTrue(sharedCount(top) >= 2)
                    }
                }
            }
            // Horizontal neighbours share their side.
            for (row in 0 until 3) {
                val keys = hex.filter { it.row == row }
                keys.zipWithNext().forEach { (left, right) ->
                    assertEquals(left.hexVertices[1].x, right.hexVertices[5].x, 0.01f)
                }
            }
        }
    }

    private fun insideHex(key: Key, x: Float, y: Float): Boolean {
        // Point-in-convex-polygon test.
        val v = key.hexVertices
        var sign = 0
        for (i in v.indices) {
            val a = v[i]
            val b = v[(i + 1) % v.size]
            val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0) {
                if (sign == 0) sign = s else if (s != sign) return false
            }
        }
        return true
    }

    @Test
    fun hexHitTestMatchesDrawnHexagons() {
        val noGap = PixelMetrics.from(Metrics(hexGap = 0f), "medium", density)
        val l = layout("qwertz", KeyboardMode.HEX, m = noGap)
        val hex = l.keys.filter { it.shape == KeyShape.HEX }
        var checked = 0
        var y = l.toolbarHeight + 1f
        while (y < l.bottomRowTop) {
            var x = 1f
            while (x < width) {
                val hit = l.hitTest(x, y)
                assertNotNull("no dead zone at $x,$y", hit)
                val containing = hex.filter { insideHex(it, x, y) }
                if (containing.size == 1) {
                    assertSame("point $x,$y", containing.single(), hit)
                    checked++
                }
                x += 7f
            }
            y += 5f
        }
        assertTrue(checked > 1000)
    }

    @Test
    fun hitTestEdgeCases() {
        val l = layout("qwertz", KeyboardMode.NORMAL)
        assertNull(l.hitTest(100f, 5f)) // toolbar
        assertEquals("q", l.hitTest(0f, l.rowsTop + 1f)!!.code) // clamped to the first key
        assertEquals("ü", l.hitTest(width - 0.5f, l.rowsTop + 1f)!!.code)
        // Below the bottom row (bottom padding) → bottom row.
        assertEquals(KeyArea.BOTTOM, l.hitTest(width / 2, l.height - 1f)!!.area)
        // Indented third row: left of shift → shift (nearest by x).
        val shiftKey = l.keys.first { it.type == KeyType.SHIFT }
        assertSame(shiftKey, l.hitTest(1f, shiftKey.centerY))
    }

    @Test
    fun bottomPunctuationForPinyin() {
        val l = layout("pinyin", KeyboardMode.NORMAL)
        val bottom = l.keys.filter { it.area == KeyArea.BOTTOM && it.type == KeyType.CHAR }
        assertEquals(listOf("，", "。"), bottom.map { it.text })
        assertEquals(listOf("，", "。"), bottom.map { it.code })
    }

    @Test
    fun popupLayout() {
        val l = layout("qwertz", KeyboardMode.NORMAL)
        val d = l.keys.first { it.code == "d" }
        val small = l.popupFor(d, listOf("ď", "đ", "ð"))
        assertEquals(1, small.rows)
        assertEquals(d.centerX, small.bounds.centerX, 0.5f)
        assertEquals(d.visual.top, small.bounds.bottom, 0.01f)
        assertEquals(0.9f * l.rowHeight, small.cells[0].height, 0.01f)
        assertEquals(0, small.selected)
        // A popup for the first row may overlap the toolbar but never leaves the keyboard.
        val e = l.keys.first { it.code == "e" }
        assertEquals(0f, l.popupFor(e, listOf("3", "è")).bounds.top, 0.01f)
        // Clamped at the left edge.
        val q = l.keys.first { it.code == "q" }
        val clamped = l.popupFor(q, listOf("1", "a", "b", "c"))
        assertEquals(metrics.sidePadding, clamped.bounds.left, 0.01f)
        // Too many items for one row → two rows, the first half in the lower row.
        val many = (1..30).map { it.toString() }
        val big = l.popupFor(l.keys.first { it.code == "u" }, many)
        assertEquals(2, big.rows)
        assertEquals(15, big.perRow)
        assertTrue(big.cells[0].top > big.cells[15].top)
        // Selection follows x, y picks the row, far below cancels.
        assertEquals(0, big.indexAt(big.cells[0].centerX, big.cells[0].centerY, l.rowHeight))
        assertEquals(17, big.indexAt(big.cells[17].centerX, big.cells[17].centerY, l.rowHeight))
        assertEquals(14, big.indexAt(width * 2, big.cells[0].centerY, l.rowHeight))
        assertEquals(-1, big.indexAt(big.cells[0].centerX, big.key.cell.bottom + 1.6f * l.rowHeight, l.rowHeight))
    }
}
