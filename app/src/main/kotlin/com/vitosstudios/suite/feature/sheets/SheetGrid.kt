package com.vitosstudios.suite.feature.sheets

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.vitosstudios.suite.xlsx.CellRange
import com.vitosstudios.suite.xlsx.CellRef
import com.vitosstudios.suite.xlsx.CellType
import com.vitosstudios.suite.xlsx.ChartInfo
import com.vitosstudios.suite.xlsx.ChartType
import com.vitosstudios.suite.xlsx.Worksheet
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Pixel geometry of one sheet at the current zoom: prefix sums of column widths and row heights. */
internal class GridGeometry(ws: Worksheet, density: Float, zoom: Float, val maxRow: Int, val maxCol: Int) {
    val headerW = 44f * density * zoom
    val headerH = 26f * density * zoom
    val colX = FloatArray(maxCol + 2)
    val rowY = FloatArray(maxRow + 2)
    val frozenRows = ws.frozenRows.coerceIn(0, maxRow)
    val frozenCols = ws.frozenColumns.coerceIn(0, maxCol)

    init {
        // Column widths are in characters of the default font (~7 px each at 100 %).
        for (c in 1..maxCol) colX[c + 1] = colX[c] + if (ws.isColumnHidden(c)) 0f else ((ws.columnWidth(c) * 7 + 5) * density * zoom * 0.95f).toFloat()
        // Row heights are in points.
        for (r in 1..maxRow) rowY[r + 1] = rowY[r] + if (ws.isRowHidden(r)) 0f else (ws.rowHeight(r) * 1.25 * density * zoom).toFloat()
    }

    fun x(col: Int): Float = colX[col.coerceIn(1, maxCol + 1)]
    fun y(row: Int): Float = rowY[row.coerceIn(1, maxRow + 1)]
    val frozenWidth: Float get() = x(frozenCols + 1)
    val frozenHeight: Float get() = y(frozenRows + 1)
    val contentWidth: Float get() = x(maxCol + 1)
    val contentHeight: Float get() = y(maxRow + 1)

    /** Column at content x (1-based), clamped. */
    fun colAt(x: Float): Int {
        var lo = 1; var hi = maxCol
        while (lo < hi) { val m = (lo + hi + 1) / 2; if (colX[m] <= x) lo = m else hi = m - 1 }
        return lo
    }

    fun rowAt(y: Float): Int {
        var lo = 1; var hi = maxRow
        while (lo < hi) { val m = (lo + hi + 1) / 2; if (rowY[m] <= y) lo = m else hi = m - 1 }
        return lo
    }
}

/**
 * Virtualised spreadsheet grid drawn on a canvas: only visible cells are read and drawn, so a
 * 10,000-row sheet scrolls smoothly. Drag scrolls (with fling), tap selects, long-press and drag
 * selects a range, double tap edits. TalkBack gets the selected cell and move/edit actions.
 */
@Composable
fun SheetGrid(vm: SheetsViewModel, modifier: Modifier = Modifier) {
    val v = vm.version
    val ws = vm.sheet() ?: return
    val density = LocalDensity.current.density
    val used = remember(v, vm.sheetIndex) { ws.usedRange() }
    val maxRow = maxOf(used?.bottom ?: 0, vm.focus.row) + 60
    val maxCol = maxOf(used?.right ?: 0, vm.focus.col) + 12
    val geo = remember(v, vm.sheetIndex, vm.zoom, maxRow, maxCol) { GridGeometry(ws, density, vm.zoom, maxRow, maxCol) }
    val merged = remember(v, vm.sheetIndex) { ws.mergedRanges }
    val charts = remember(v, vm.sheetIndex) { try { ws.charts() } catch (_: Exception) { emptyList() } }
    var scrollX by remember(vm.sheetIndex) { mutableFloatStateOf(0f) }
    var scrollY by remember(vm.sheetIndex) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var fling by remember { mutableStateOf<Job?>(null) }
    val paints = remember { GridPaints(density) }

    fun clampScroll(w: Float, h: Float) {
        scrollX = scrollX.coerceIn(0f, maxOf(0f, geo.contentWidth - (w - geo.headerW - geo.frozenWidth)))
        scrollY = scrollY.coerceIn(0f, maxOf(0f, geo.contentHeight - (h - geo.headerH - geo.frozenHeight)))
    }

    fun cellAt(pos: Offset): CellRef {
        val cx = pos.x - geo.headerW
        val cy = pos.y - geo.headerH
        val col = if (cx < geo.frozenWidth) geo.colAt(cx) else geo.colAt(cx + scrollX)
        val row = if (cy < geo.frozenHeight) geo.rowAt(cy) else geo.rowAt(cy + scrollY)
        return CellRef(row, col)
    }

    val focusCell = vm.cell(vm.focus.row, vm.focus.col)
    Canvas(
        modifier
            .semantics {
                contentDescription = "Sheet ${ws.name}, cell ${vm.focus.a1}" + (focusCell?.display?.takeIf { it.isNotEmpty() }?.let { ", $it" } ?: ", empty") +
                    (focusCell?.formula?.let { ", formula $it" } ?: "")
                customActions = listOf(
                    CustomAccessibilityAction("Move right") { vm.move(0, 1); true },
                    CustomAccessibilityAction("Move left") { vm.move(0, -1); true },
                    CustomAccessibilityAction("Move down") { vm.move(1, 0); true },
                    CustomAccessibilityAction("Move up") { vm.move(-1, 0); true },
                    CustomAccessibilityAction("Edit cell") { vm.startEditing(); true },
                )
            }
            .pointerInput(geo) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fling?.cancel()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val slop = viewConfiguration.touchSlop
                    var moved = false
                    var total = Offset.Zero
                    // Long press without movement starts range selection.
                    val longPress = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Main)
                            val ch = ev.changes.first()
                            if (!ch.pressed) return@withTimeoutOrNull false
                            total += ch.positionChange()
                            if (total.getDistance() > slop) { moved = true; return@withTimeoutOrNull false }
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    } == null
                    if (longPress) {
                        val start = cellAt(down.position)
                        vm.select(start.row, start.col)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.first()
                            if (!ch.pressed) break
                            val c = cellAt(ch.position)
                            vm.select(c.row, c.col, extend = true)
                            ch.consume()
                        }
                        return@awaitEachGesture
                    }
                    if (!moved) {
                        // Tap or double tap.
                        val c = cellAt(down.position)
                        if (down.position.x < geo.headerW || down.position.y < geo.headerH) return@awaitEachGesture
                        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown(requireUnconsumed = false) }
                        if (second != null && cellAt(second.position) == c) {
                            vm.select(c.row, c.col)
                            vm.startEditing()
                        } else {
                            vm.select(c.row, c.col)
                        }
                        return@awaitEachGesture
                    }
                    // Scrolling.
                    scrollX += -total.x
                    scrollY += -total.y
                    clampScroll(size.width.toFloat(), size.height.toFloat())
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.first()
                        if (!ch.pressed) break
                        val d = ch.positionChange()
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        scrollX -= d.x
                        scrollY -= d.y
                        clampScroll(size.width.toFloat(), size.height.toFloat())
                        ch.consume()
                    }
                    val vel = tracker.calculateVelocity()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    fling = scope.launch {
                        val decay = splineBasedDecay<Float>(this@pointerInput)
                        launch {
                            var last = 0f
                            AnimationState(0f, -vel.x).animateDecay(decay) { val dv = value - last; last = value; scrollX += dv; clampScroll(w, h) }
                        }
                        launch {
                            var last = 0f
                            AnimationState(0f, -vel.y).animateDecay(decay) { val dv = value - last; last = value; scrollY += dv; clampScroll(w, h) }
                        }
                    }
                }
            },
    ) {
        clampScroll(size.width, size.height)
        drawIntoCanvas { canvas ->
            drawGrid(canvas.nativeCanvas, vm, ws, geo, merged, charts, scrollX, scrollY, size.width, size.height, paints)
        }
    }
}

internal class GridPaints(density: Float) {
    val grid = Paint().apply { color = 0xFFE0E0E0.toInt(); strokeWidth = 1f }
    val header = Paint().apply { color = 0xFFF1F3F4.toInt() }
    val headerSel = Paint().apply { color = 0xFFD3E3FD.toInt() }
    val headerText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF444746.toInt(); textSize = 12f * density; textAlign = Paint.Align.CENTER }
    val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    val fill = Paint()
    val selFill = Paint().apply { color = 0x221A73E8 }
    val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A73E8.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density }
    val border = Paint().apply { color = 0xFF000000.toInt(); strokeWidth = 1.2f }
    val frozenLine = Paint().apply { color = 0xFF9AA0A6.toInt(); strokeWidth = 2f * density }
    val white = Paint().apply { color = 0xFFFFFFFF.toInt() }
    val chartBox = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    val chartStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA0A6.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    val density = density
}

private val seriesColors = intArrayOf(0xFF4472C4.toInt(), 0xFFED7D31.toInt(), 0xFFA5A5A5.toInt(), 0xFFFFC000.toInt(), 0xFF5B9BD5.toInt(), 0xFF70AD47.toInt())

private fun drawGrid(
    c: android.graphics.Canvas, vm: SheetsViewModel, ws: Worksheet, geo: GridGeometry, merged: List<CellRange>, charts: List<ChartInfo>,
    sx: Float, sy: Float, w: Float, h: Float, p: GridPaints,
) {
    c.drawRect(0f, 0f, w, h, p.white)
    val fw = geo.frozenWidth
    val fh = geo.frozenHeight
    val ox = geo.headerW
    val oy = geo.headerH
    // Visible column/row ranges for the scrolling part.
    val firstCol = geo.colAt(sx + fw)
    val lastCol = geo.colAt(sx + w - ox)
    val firstRow = geo.rowAt(sy + fh)
    val lastRow = geo.rowAt(sy + h - oy)
    val colsScroll = (maxOf(firstCol, geo.frozenCols + 1))..lastCol
    val rowsScroll = (maxOf(firstRow, geo.frozenRows + 1))..lastRow
    val colsFrozen = 1..geo.frozenCols
    val rowsFrozen = 1..geo.frozenRows

    fun screenX(col: Int): Float = ox + if (col <= geo.frozenCols) geo.x(col) else geo.x(col) - sx
    fun screenY(row: Int): Float = oy + if (row <= geo.frozenRows) geo.y(row) else geo.y(row) - sy

    fun region(rows: IntRange, cols: IntRange, clip: RectF) {
        if (rows.isEmpty() || cols.isEmpty()) return
        c.save()
        c.clipRect(clip)
        // Fills and borders.
        for (r in rows) for (col in cols) {
            val cell = vm.cell(r, col) ?: continue
            val st = cell.style
            val x0 = screenX(col); val x1 = screenX(col + 1); val y0 = screenY(r); val y1 = screenY(r + 1)
            st.fillColor?.let { p.fill.color = it; c.drawRect(x0, y0, x1, y1, p.fill) }
        }
        // Grid lines.
        for (col in cols) { val x = screenX(col); c.drawLine(x, clip.top, x, clip.bottom, p.grid) }
        for (r in rows) { val y = screenY(r); c.drawLine(clip.left, y, clip.right, y, p.grid) }
        // Text and explicit borders.
        for (r in rows) for (col in cols) {
            val cell = vm.cell(r, col) ?: continue
            val m = merged.firstOrNull { it.contains(r, col) }
            if (m != null && (m.top != r || m.left != col)) continue
            val st = cell.style
            val x0 = screenX(col); val y0 = screenY(r)
            val x1 = if (m != null) screenX(m.right + 1) else screenX(col + 1)
            val y1 = if (m != null) screenY(m.bottom + 1) else screenY(r + 1)
            if (m != null) {
                c.drawRect(x0 + 1, y0 + 1, x1 - 1, y1 - 1, p.white)
                st.fillColor?.let { p.fill.color = it; c.drawRect(x0, y0, x1, y1, p.fill) }
            }
            for ((side, style) in listOf(0 to st.borderLeft, 1 to st.borderRight, 2 to st.borderTop, 3 to st.borderBottom)) {
                if (style == null || style == "none") continue
                p.border.strokeWidth = if (style.contains("thick") || style.contains("medium")) 2.5f else 1.2f
                when (side) {
                    0 -> c.drawLine(x0, y0, x0, y1, p.border)
                    1 -> c.drawLine(x1, y0, x1, y1, p.border)
                    2 -> c.drawLine(x0, y0, x1, y0, p.border)
                    else -> c.drawLine(x0, y1, x1, y1, p.border)
                }
            }
            val text = cell.display
            if (text.isEmpty()) continue
            p.text.textSize = (st.fontSize * 1.25 * p.density * vm.zoom).toFloat()
            p.text.color = st.fontColor ?: if (cell.type == CellType.ERROR) 0xFFC5221F.toInt() else 0xFF202124.toInt()
            p.text.typeface = Typeface.create(Typeface.SANS_SERIF, when {
                st.bold && st.italic -> Typeface.BOLD_ITALIC
                st.bold -> Typeface.BOLD
                st.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            })
            p.text.isUnderlineText = st.underline
            p.text.isStrikeThruText = st.strike
            val pad = 4f * p.density
            val align = st.horizontal ?: if (cell.type == CellType.NUMBER || cell.type == CellType.BOOLEAN) "right" else "left"
            val tw = p.text.measureText(text)
            val tx = when (align) {
                "center", "centerContinuous" -> (x0 + x1) / 2 - tw / 2
                "right" -> x1 - pad - tw
                else -> x0 + pad
            }
            val ty = when (st.vertical) {
                "top" -> y0 + p.text.textSize
                "center" -> (y0 + y1) / 2 + p.text.textSize / 3
                else -> y1 - pad
            }
            c.save()
            c.clipRect(x0, y0, x1, y1)
            c.drawText(text, tx, ty, p.text)
            c.restore()
        }
        c.restore()
    }

    val right = w
    val bottom = h
    region(rowsScroll, colsScroll, RectF(ox + fw, oy + fh, right, bottom))
    region(rowsFrozen, colsScroll, RectF(ox + fw, oy, right, oy + fh))
    region(rowsScroll, colsFrozen, RectF(ox, oy + fh, ox + fw, bottom))
    region(rowsFrozen, colsFrozen, RectF(ox, oy, ox + fw, oy + fh))

    // Charts (existing ones are shown, never changed).
    c.save()
    c.clipRect(ox + fw, oy + fh, right, bottom)
    for (chart in charts) {
        val a = chart.anchor ?: continue
        // Anchors are 1-based cells plus offsets in EMU (9525 EMU = 1 px at 96 dpi).
        val k = p.density * vm.zoom / 9525f
        val x0 = screenX(a.from.col) + a.fromColOffsetEmu * k
        val y0 = screenY(a.from.row) + a.fromRowOffsetEmu * k
        val x1 = a.to?.let { screenX(it.col) + a.toColOffsetEmu * k } ?: (x0 + (a.widthEmu ?: 3_000_000L) * k)
        val y1 = a.to?.let { screenY(it.row) + a.toRowOffsetEmu * k } ?: (y0 + (a.heightEmu ?: 2_000_000L) * k)
        drawChart(c, chart, RectF(x0, y0, x1, y1), p)
    }
    c.restore()

    // Selection.
    val sel = vm.selection
    val selRect = RectF(screenX(sel.left), screenY(sel.top), screenX(sel.right + 1), screenY(sel.bottom + 1))
    c.save()
    c.clipRect(ox, oy, right, bottom)
    c.drawRect(selRect, p.selFill)
    c.drawRect(selRect, p.selStroke)
    c.restore()

    // Headers.
    c.drawRect(0f, 0f, w, oy, p.header)
    c.drawRect(0f, 0f, ox, h, p.header)
    p.headerText.textSize = 12f * p.density * vm.zoom
    for (col in colsFrozen + colsScroll) {
        val x0 = screenX(col); val x1 = screenX(col + 1)
        if (x1 < ox) continue
        if (col in sel.left..sel.right) c.drawRect(x0, 0f, x1, oy, p.headerSel)
        c.drawLine(x0, 0f, x0, oy, p.grid)
        c.drawText(CellRef.columnName(col), (x0 + x1) / 2, oy * 0.68f, p.headerText)
    }
    for (r in rowsFrozen + rowsScroll) {
        val y0 = screenY(r); val y1 = screenY(r + 1)
        if (y1 < oy) continue
        if (r in sel.top..sel.bottom) c.drawRect(0f, y0, ox, y1, p.headerSel)
        c.drawLine(0f, y0, ox, y0, p.grid)
        c.drawText(r.toString(), ox / 2, (y0 + y1) / 2 + p.headerText.textSize / 3, p.headerText)
    }
    if (geo.frozenCols > 0) c.drawLine(ox + fw, 0f, ox + fw, h, p.frozenLine)
    if (geo.frozenRows > 0) c.drawLine(0f, oy + fh, w, oy + fh, p.frozenLine)
}

private fun drawChart(c: android.graphics.Canvas, chart: ChartInfo, r: RectF, p: GridPaints) {
    c.drawRect(r, p.chartBox)
    c.drawRect(r, p.chartStroke)
    val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * p.density; color = 0xFF202124.toInt(); typeface = Typeface.DEFAULT_BOLD }
    chart.title?.let { c.drawText(it, r.left + 8 * p.density, r.top + 18 * p.density, title) }
    val area = RectF(r.left + 12 * p.density, r.top + 28 * p.density, r.right - 12 * p.density, r.bottom - 12 * p.density)
    if (area.width() <= 0 || area.height() <= 0) return
    val series = chart.series.filter { s -> s.values.any { it != null } }
    if (chart.type == ChartType.UNSUPPORTED || series.isEmpty()) {
        title.typeface = Typeface.DEFAULT
        c.drawText("🔒 ${chart.typeName} chart (kept unchanged)", area.left, area.centerY(), title)
        return
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    when (chart.type) {
        ChartType.PIE -> {
            val values = series.first().values.map { (it ?: 0.0).coerceAtLeast(0.0) }
            val total = values.sum().takeIf { it > 0 } ?: return
            val d = minOf(area.width(), area.height())
            val oval = RectF(area.centerX() - d / 2, area.centerY() - d / 2, area.centerX() + d / 2, area.centerY() + d / 2)
            var start = -90f
            values.forEachIndexed { i, v ->
                val sweep = (v / total * 360).toFloat()
                paint.color = seriesColors[i % seriesColors.size]
                c.drawArc(oval, start, sweep, true, paint)
                start += sweep
            }
        }
        ChartType.LINE -> {
            val all = series.flatMap { it.values.filterNotNull() }
            val max = all.maxOrNull() ?: return
            val min = minOf(0.0, all.minOrNull() ?: 0.0)
            val span = (max - min).takeIf { it > 0 } ?: 1.0
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * p.density
            series.forEachIndexed { si, s ->
                paint.color = seriesColors[si % seriesColors.size]
                val n = s.values.size
                var prev: Pair<Float, Float>? = null
                s.values.forEachIndexed { i, v ->
                    if (v == null) { prev = null; return@forEachIndexed }
                    val x = area.left + if (n <= 1) area.width() / 2 else area.width() * i / (n - 1)
                    val y = (area.bottom - (v - min) / span * area.height()).toFloat()
                    prev?.let { (px, py) -> c.drawLine(px, py, x, y, paint) }
                    prev = x to y
                }
            }
        }
        else -> {
            val all = series.flatMap { it.values.filterNotNull() }
            val max = maxOf(0.0, all.maxOrNull() ?: 0.0)
            val min = minOf(0.0, all.minOrNull() ?: 0.0)
            val span = (max - min).takeIf { it > 0 } ?: 1.0
            val n = series.maxOf { it.values.size }
            val groupW = area.width() / n
            val barW = groupW * 0.8f / series.size
            val horizontal = chart.type == ChartType.BAR
            series.forEachIndexed { si, s ->
                paint.color = seriesColors[si % seriesColors.size]
                s.values.forEachIndexed { i, v ->
                    if (v == null) return@forEachIndexed
                    if (horizontal) {
                        val groupH = area.height() / n
                        val barH = groupH * 0.8f / series.size
                        val y = area.top + groupH * i + groupH * 0.1f + barH * si
                        val zero = (area.left + (-min) / span * area.width()).toFloat()
                        val x = (area.left + (v - min) / span * area.width()).toFloat()
                        c.drawRect(minOf(zero, x), y, maxOf(zero, x), y + barH, paint)
                    } else {
                        val x = area.left + groupW * i + groupW * 0.1f + barW * si
                        val zero = (area.bottom - (-min) / span * area.height()).toFloat()
                        val y = (area.bottom - (v - min) / span * area.height()).toFloat()
                        c.drawRect(x, minOf(zero, y), x + barW, maxOf(zero, y), paint)
                    }
                }
            }
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = abs(cos(0.0) + sin(0.0))
}
