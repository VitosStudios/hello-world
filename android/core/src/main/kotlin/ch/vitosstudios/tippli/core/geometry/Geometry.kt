package ch.vitosstudios.tippli.core.geometry

import ch.vitosstudios.tippli.core.data.KeySpec
import ch.vitosstudios.tippli.core.data.KeyboardDefinition
import ch.vitosstudios.tippli.core.data.KeyboardSpec
import ch.vitosstudios.tippli.core.data.LayoutsFile
import ch.vitosstudios.tippli.core.data.Metrics
import ch.vitosstudios.tippli.core.settings.KeyboardMode
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Axis-aligned rectangle in pixels. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    fun inset(d: Float) = Box(left + d, top + d, right - d, bottom - d)
}

data class Point(val x: Float, val y: Float)

enum class Page { LETTERS, SYMBOLS1, SYMBOLS2 }

enum class KeyType {
    CHAR, SHIFT, BACK, SPACE, ENTER, SYMBOLS, SYMBOLS2, SYMBOLS1, LETTERS, EMOJI, NEXT_KEYBOARD, UNKNOWN;

    /** Keys that type text on a tap (used by the fast typing rule, SPEC §4.1). */
    val typesCharacter: Boolean get() = this == CHAR || this == SPACE

    companion object {
        fun of(code: String): KeyType = when (code) {
            "\$shift" -> SHIFT
            "\$back" -> BACK
            "\$space" -> SPACE
            "\$enter" -> ENTER
            "\$123" -> SYMBOLS
            "\$sym2" -> SYMBOLS2
            "\$sym1" -> SYMBOLS1
            "\$abc" -> LETTERS
            "\$emoji" -> EMOJI
            "\$nextkb" -> NEXT_KEYBOARD
            else -> if (code.startsWith("$")) UNKNOWN else CHAR
        }
    }
}

enum class KeyArea { NUMBER_ROW, LETTERS, BOTTOM }

enum class KeyShape { RECT, HEX }

/**
 * One key of a laid-out keyboard. [cell] is the full row cell used for hit
 * testing (SPEC §3.5); the visible shape is [visual] (rect) or [hexVertices].
 */
data class Key(
    val index: Int,
    /** The code from layouts.json, e.g. `"a"` or `"$shift"`. */
    val code: String,
    val type: KeyType,
    /** Text a tap types (CHAR keys, after `bottomPunctuation` mapping); empty for special keys. */
    val text: String,
    val area: KeyArea,
    /** Row index within the letter rows (0 = first letter row); -1 for number and bottom row. */
    val row: Int,
    /** Index of the key within its row. */
    val column: Int,
    val shape: KeyShape,
    val cell: Box,
    val visual: Box,
    val hexVertices: List<Point> = emptyList(),
    val cornerRadius: Float,
    /** For hex mode: true for the first of two adjacent `$space` keys (only it shows the label). */
    val primarySpace: Boolean = true,
) {
    val centerX get() = visual.centerX
    val centerY get() = visual.centerY
    val isCharacter get() = type == KeyType.CHAR
}

/** Pixel metrics derived from theme.json `metrics` and the display density. */
data class PixelMetrics(
    val toolbarHeight: Float,
    val rowHeight: Float,
    val keyGap: Float,
    val hexGap: Float,
    val keyCornerRadius: Float,
    val hexCornerRadius: Float,
    val keyLabelSize: Float,
    val specialLabelSize: Float,
    val sidePadding: Float,
    val bottomPadding: Float,
    val density: Float,
) {
    companion object {
        fun from(metrics: Metrics, keyHeight: String, density: Float, rowScale: Float = 1f) = PixelMetrics(
            toolbarHeight = metrics.toolbarHeight * density,
            rowHeight = metrics.rowHeightFor(keyHeight) * density * rowScale,
            keyGap = metrics.keyGap * density,
            hexGap = metrics.hexGap * density,
            keyCornerRadius = metrics.keyCornerRadius * density,
            hexCornerRadius = metrics.hexCornerRadius * density,
            keyLabelSize = metrics.keyLabelSize * density,
            specialLabelSize = metrics.specialLabelSize * density,
            sidePadding = metrics.sidePadding * density,
            bottomPadding = metrics.bottomPadding * density,
            density = density,
        )
    }
}

/** A fully laid-out keyboard page with hit testing (SPEC §3). */
class KeyboardLayout(
    val width: Float,
    val height: Float,
    val metrics: PixelMetrics,
    /** Geometry actually used: symbol pages are always [KeyboardMode.NORMAL]. */
    val geometryMode: KeyboardMode,
    val page: Page,
    val keys: List<Key>,
    /** Width of one layout unit `u` of the letter rows. */
    val unit: Float,
    /** Top of the first normal row (number row or first letter row). */
    val rowsTop: Float,
    /** Number of normal (rectangular) rows above the bottom row. */
    val normalRowCount: Int,
    /**
     * Height of the normal rows above the bottom row: H on the letters page; on
     * symbol pages the rows share the letters-page height evenly (SPEC §3.7).
     */
    val normalRowHeight: Float,
    val bottomRowTop: Float,
) {
    val toolbarHeight get() = metrics.toolbarHeight
    val rowHeight get() = metrics.rowHeight
    val bottomRowBottom get() = bottomRowTop + rowHeight

    private val bottomKeys = keys.filter { it.area == KeyArea.BOTTOM }
    private val hexKeys = keys.filter { it.shape == KeyShape.HEX }
    private val rowsOfNormalKeys: List<List<Key>> = run {
        val normal = keys.filter { it.area != KeyArea.BOTTOM && it.shape == KeyShape.RECT }
        val rows = normal.groupBy { it.cell.top }.toSortedMap().values.toList()
        rows
    }

    /** Hex hit metric factor `k = (√3/2)·u/H` (SPEC §3.5). */
    val hexMetricK: Float = (sqrt(3.0).toFloat() / 2f) * unit / metrics.rowHeight

    fun key(code: String): Key? = keys.firstOrNull { it.code == code }

    /** SPEC §3.5. Returns null in the toolbar (or outside the keyboard). */
    fun hitTest(x: Float, y: Float): Key? {
        if (y < toolbarHeight) return null
        if (y >= bottomRowTop) return nearestByX(bottomKeys, x)
        if (geometryMode == KeyboardMode.HEX && hexKeys.isNotEmpty()) {
            var best: Key? = null
            var bestD = Float.MAX_VALUE
            for (k in hexKeys) {
                val dx = x - k.centerX
                val dy = (y - k.centerY) * hexMetricK
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = k }
            }
            return best
        }
        if (rowsOfNormalKeys.isEmpty()) return nearestByX(bottomKeys, x)
        val row = floor((y - rowsTop) / normalRowHeight).toInt().coerceIn(0, rowsOfNormalKeys.size - 1)
        return nearestByX(rowsOfNormalKeys[row], x)
    }

    private fun nearestByX(row: List<Key>, x: Float): Key? {
        if (row.isEmpty()) return null
        row.firstOrNull { x >= it.cell.left && x < it.cell.right }?.let { return it }
        if (x < row.first().cell.left) return row.first()
        if (x >= row.last().cell.right) return row.last()
        return row.minByOrNull { k -> min(kotlin.math.abs(x - k.cell.left), kotlin.math.abs(x - k.cell.right)) }
    }

    /**
     * Long-press popup layout (SPEC §4.4): one row of cells above the key (cell
     * width = u, min 30 dp, height 0.9·H), centred on the key and clamped to the
     * keyboard; two rows when one does not fit. The first half of the items is
     * in the row nearest to the key.
     */
    fun popupFor(key: Key, items: List<String>): Popup {
        val cellW = max(unit, 30f * metrics.density)
        val cellH = 0.9f * rowHeight
        val available = width - 2 * metrics.sidePadding
        val rows = if (items.size * cellW <= available || items.size < 2) 1 else 2
        val perRow = if (rows == 1) items.size else (items.size + 1) / 2
        val rowWidth = perRow * cellW
        val keyTop = key.visual.top
        val popupHeight = rows * cellH
        var left = key.centerX - rowWidth / 2f
        left = left.coerceIn(metrics.sidePadding, max(metrics.sidePadding, width - metrics.sidePadding - rowWidth))
        val top = max(0f, keyTop - popupHeight)
        val cells = items.indices.map { i ->
            val r = if (rows == 1) 0 else i / perRow
            val c = if (rows == 1) i else i % perRow
            // Row 0 is the lower row (closest to the finger).
            val cellTop = top + (rows - 1 - r) * cellH
            Box(left + c * cellW, cellTop, left + (c + 1) * cellW, cellTop + cellH)
        }
        return Popup(key, items, cells, rows, perRow, Box(left, top, left + rowWidth, top + popupHeight))
    }
}

/** An open long-press popup (SPEC §4.4). [selected] is -1 when the selection was cancelled. */
data class Popup(
    val key: Key,
    val items: List<String>,
    val cells: List<Box>,
    val rows: Int,
    val perRow: Int,
    val bounds: Box,
    val selected: Int = 0,
) {
    /** Index of the item under a pointer at (x, y), or -1 when the pointer cancels (SPEC §4.4). */
    fun indexAt(x: Float, y: Float, rowHeight: Float): Int {
        if (items.isEmpty()) return -1
        if (y > key.cell.bottom + 1.5f * rowHeight) return -1
        val row = if (rows == 1) 0 else {
            val lowerRowTop = cells[0].top
            if (y < lowerRowTop) 1 else 0
        }
        val start = row * perRow
        val end = min(items.size, start + perRow) - 1
        if (start > end) return end.coerceAtLeast(0)
        val cellW = cells[0].width
        val col = floor((x - bounds.left) / cellW).toInt()
        return (start + col).coerceIn(start, end)
    }
}

/** Everything needed to lay out one keyboard page. */
data class LayoutRequest(
    val width: Float,
    val metrics: PixelMetrics,
    val layouts: LayoutsFile,
    val keyboard: KeyboardDefinition,
    val mode: KeyboardMode,
    val page: Page,
    val numberRow: Boolean,
)

/** Builds [KeyboardLayout]s from layouts.json definitions (SPEC §3.1–3.4, §3.7). */
object LayoutBuilder {

    /**
     * Total keyboard height for the letters page (SPEC §3.1). All pages and the
     * emoji/tips panels use this height so the keyboard never jumps.
     */
    fun totalHeight(m: PixelMetrics, mode: KeyboardMode, numberRow: Boolean): Float {
        val h = m.rowHeight
        return if (mode == KeyboardMode.HEX) {
            m.toolbarHeight + 4 * h + h / 3f + m.bottomPadding
        } else {
            val rows = 4 + if (numberRow) 1 else 0
            m.toolbarHeight + rows * h + m.bottomPadding
        }
    }

    fun build(req: LayoutRequest): KeyboardLayout {
        val m = req.metrics
        val h = m.rowHeight
        val w = req.width
        val sp = m.sidePadding
        val total = totalHeight(m, req.mode, req.numberRow)
        val keys = ArrayList<Key>()

        val spec: KeyboardSpec
        val bottom: List<KeySpec>
        val geometryMode: KeyboardMode
        val punctuation: Map<String, String>
        when (req.page) {
            Page.LETTERS -> {
                geometryMode = req.mode
                spec = if (req.mode == KeyboardMode.HEX) req.keyboard.hex else req.keyboard.normal
                bottom = req.layouts.bottomRows[if (req.mode == KeyboardMode.HEX) "hex" else "normal"].orEmpty()
                punctuation = req.keyboard.bottomPunctuation
            }
            Page.SYMBOLS1, Page.SYMBOLS2 -> {
                geometryMode = KeyboardMode.NORMAL
                spec = req.layouts.symbolPages[if (req.page == Page.SYMBOLS1) "symbols1" else "symbols2"]
                    ?: KeyboardSpec()
                bottom = req.layouts.bottomRows["symbols"].orEmpty()
                punctuation = emptyMap()
            }
        }
        val unit = (w - 2 * sp) / spec.width.toFloat().coerceAtLeast(1f)
        val bottomRowTop = total - m.bottomPadding - h

        val rowsTop: Float
        var normalRows = 0
        var normalRowHeight = h
        if (geometryMode == KeyboardMode.HEX) {
            rowsTop = m.toolbarHeight + h / 6f
            spec.rows.forEachIndexed { r, row -> addHexRow(keys, row.keys, row.indent, r, rowsTop + r * h, unit, m, punctuation) }
        } else {
            val withNumberRow = req.page == Page.LETTERS && req.numberRow
            val rowCount = (spec.rows.size + if (withNumberRow) 1 else 0).coerceAtLeast(1)
            // The rows share the space between toolbar and bottom row evenly: exactly H on the
            // letters page; taller rows on symbol pages, which keep the letters-page height.
            rowsTop = m.toolbarHeight
            normalRowHeight = (bottomRowTop - m.toolbarHeight) / rowCount
            var y = rowsTop
            if (withNumberRow) {
                addScaledRow(keys, req.layouts.numberRow, y, w, normalRowHeight, m, KeyArea.NUMBER_ROW, emptyMap())
                y += normalRowHeight
            }
            spec.rows.forEachIndexed { r, row ->
                addUnitRow(keys, row.keys, row.indent, r, y, normalRowHeight, unit, m, punctuation)
                y += normalRowHeight
            }
            normalRows = rowCount
        }
        addScaledRow(keys, bottom, bottomRowTop, w, h, m, KeyArea.BOTTOM, punctuation)

        return KeyboardLayout(
            width = w,
            height = total,
            metrics = m,
            geometryMode = geometryMode,
            page = req.page,
            keys = keys,
            unit = unit,
            rowsTop = rowsTop,
            normalRowCount = normalRows,
            normalRowHeight = normalRowHeight,
            bottomRowTop = bottomRowTop,
        )
    }

    private fun textFor(code: String, punctuation: Map<String, String>): String =
        if (KeyType.of(code) == KeyType.CHAR) punctuation[code] ?: code else ""

    private fun codeFor(code: String, punctuation: Map<String, String>): String =
        if (KeyType.of(code) == KeyType.CHAR) punctuation[code] ?: code else code

    /** A normal row measured in layout units (SPEC §3.2). */
    private fun addUnitRow(
        keys: MutableList<Key>, row: List<KeySpec>, indent: Double, rowIndex: Int, top: Float,
        rowHeight: Float, unit: Float, m: PixelMetrics, punctuation: Map<String, String>,
    ) {
        var x = m.sidePadding + indent.toFloat() * unit
        row.forEachIndexed { i, spec ->
            val kw = spec.width.toFloat() * unit
            val cell = Box(x, top, x + kw, top + rowHeight)
            keys += rectKey(keys.size, spec.key, cell, KeyArea.LETTERS, rowIndex, i, m, punctuation)
            x += kw
        }
    }

    /** Bottom row and number row: weights scaled to fill the width (SPEC §3.2). */
    private fun addScaledRow(
        keys: MutableList<Key>, row: List<KeySpec>, top: Float, width: Float, rowHeight: Float,
        m: PixelMetrics, area: KeyArea, punctuation: Map<String, String>,
    ) {
        val sum = row.sumOf { it.width }.toFloat()
        if (sum <= 0f) return
        val scale = (width - 2 * m.sidePadding) / sum
        var x = m.sidePadding
        row.forEachIndexed { i, spec ->
            val kw = spec.width.toFloat() * scale
            val cell = Box(x, top, x + kw, top + rowHeight)
            keys += rectKey(keys.size, spec.key, cell, area, -1, i, m, punctuation)
            x += kw
        }
    }

    private fun rectKey(
        index: Int, rawCode: String, cell: Box, area: KeyArea, row: Int, column: Int,
        m: PixelMetrics, punctuation: Map<String, String>,
    ) = Key(
        index = index,
        code = codeFor(rawCode, punctuation),
        type = KeyType.of(rawCode),
        text = textFor(rawCode, punctuation),
        area = area,
        row = row,
        column = column,
        shape = KeyShape.RECT,
        cell = cell,
        visual = cell.inset(m.keyGap / 2f),
        cornerRadius = m.keyCornerRadius,
    )

    /** A hex row (SPEC §3.4): pointy-top hexagons, vertically stretched. */
    private fun addHexRow(
        keys: MutableList<Key>, row: List<KeySpec>, indent: Double, rowIndex: Int, top: Float,
        unit: Float, m: PixelMetrics, punctuation: Map<String, String>,
    ) {
        val hRow = m.rowHeight
        val cy = top + hRow / 2f
        val hexH = 4f * hRow / 3f - m.hexGap
        var offset = indent.toFloat()
        var previousWasSpace = false
        row.forEachIndexed { i, spec ->
            val kw = spec.width.toFloat()
            val cx = m.sidePadding + (offset + kw / 2f) * unit
            val a = kw * unit / 2f - m.hexGap / 2f
            val vertices = listOf(
                Point(cx, cy - hexH / 2f),
                Point(cx + a, cy - hexH / 4f),
                Point(cx + a, cy + hexH / 4f),
                Point(cx, cy + hexH / 2f),
                Point(cx - a, cy + hexH / 4f),
                Point(cx - a, cy - hexH / 4f),
            )
            val type = KeyType.of(spec.key)
            val left = m.sidePadding + offset * unit
            keys += Key(
                index = keys.size,
                code = codeFor(spec.key, punctuation),
                type = type,
                text = textFor(spec.key, punctuation),
                area = KeyArea.LETTERS,
                row = rowIndex,
                column = i,
                shape = KeyShape.HEX,
                cell = Box(left, top, left + kw * unit, top + hRow),
                visual = Box(cx - a, cy - hexH / 2f, cx + a, cy + hexH / 2f),
                hexVertices = vertices,
                cornerRadius = m.hexCornerRadius,
                primarySpace = !(type == KeyType.SPACE && previousWasSpace),
            )
            previousWasSpace = type == KeyType.SPACE
            offset += kw
        }
    }
}
