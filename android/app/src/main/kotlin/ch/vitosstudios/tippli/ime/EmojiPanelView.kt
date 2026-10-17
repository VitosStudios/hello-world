package ch.vitosstudios.tippli.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Emoji panel (SPEC §4.9): a scrollable grid with section headers and a
 * category bar at the bottom ("ABC", ⏱ recent, the categories of emoji.json
 * and ⌫). Drawn on a Canvas; only visible rows are drawn.
 */
@SuppressLint("ViewConstructor")
class EmojiPanelView(context: Context, private val ime: TippliImeService) : View(context) {

    var theme: KeyboardTheme = ime.keyboardTheme
        set(value) {
            field = value
            invalidate()
        }

    private class Section(val icon: String, val title: String, val emoji: List<String>) {
        var top = 0f
        var height = 0f
    }

    private class BarItem(val kind: Int, val icon: String, val section: Int, val rect: RectF = RectF())

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private var sections: List<Section> = emptyList()
    private var bar: List<BarItem> = emptyList()
    private var columns = 8
    private var cell = 0f
    private var offset = 0f
    private var contentHeight = 0f

    private val headerHeight get() = dp(28f)
    private val barHeight get() = dp(44f)
    private val gridHeight get() = height - barHeight

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val config = ViewConfiguration.get(context)
    private val touchSlop = config.scaledTouchSlop.toFloat()

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var pressedBar: BarItem? = null
    private var activePointer = -1

    private val backspaceRepeat = object : Runnable {
        override fun run() {
            ime.controller.emojiBackspace()
            postDelayed(this, BACKSPACE_REPEAT_MS)
        }
    }

    /** Rebuilds the sections (recents first) – called whenever the panel opens. */
    fun refresh() {
        val strings = ime.strings
        val recents = ime.settings.emojiRecents
        val list = ArrayList<Section>()
        if (recents.isNotEmpty()) list += Section(RECENT_ICON, "", recents)
        ime.app.shared.emoji.forEach { list += Section(it.icon, it.name, it.emoji) }
        sections = list
        val items = ArrayList<BarItem>()
        items += BarItem(KIND_ABC, strings["keys.letters"], -1)
        items += BarItem(KIND_SECTION, RECENT_ICON, if (recents.isNotEmpty()) 0 else -1)
        list.forEachIndexed { i, s -> if (s.icon != RECENT_ICON) items += BarItem(KIND_SECTION, s.icon, i) }
        items += BarItem(KIND_BACK, "⌫", -1)
        bar = items
        offset = 0f
        scroller.forceFinished(true)
        layoutContent()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutContent()
    }

    private fun layoutContent() {
        if (width == 0) return
        columns = max(8, (width / density / 46f).toInt())
        cell = width.toFloat() / columns
        var y = 0f
        for (s in sections) {
            s.top = y
            val rows = ceil(s.emoji.size / columns.toDouble()).toInt()
            s.height = (if (s.title.isEmpty()) dp(8f) else headerHeight) + rows * cell
            y += s.height
        }
        contentHeight = y + dp(8f)
        // Bar: ABC and ⌫ are wider than the category icons.
        val units = (bar.size - 2) + 2 * 1.6f
        val unit = width / units
        var x = 0f
        for (item in bar) {
            val w = if (item.kind == KIND_SECTION) unit else unit * 1.6f
            item.rect.set(x, height - barHeight, x + w, height.toFloat())
            x += w
        }
        offset = offset.coerceIn(0f, maxOffset())
    }

    private fun maxOffset() = max(0f, contentHeight - gridHeight)

    private fun sectionHeader(s: Section) = if (s.title.isEmpty()) dp(8f) else headerHeight

    override fun onDraw(canvas: Canvas) {
        val p = theme.palette
        canvas.drawColor(p.background)
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), gridHeight)
        paint.textSize = cell * 0.62f
        val emojiBaselineShift = -(paint.ascent() + paint.descent()) / 2f
        for (s in sections) {
            val top = s.top - offset
            if (top > gridHeight || top + s.height < 0f) continue
            if (s.title.isNotEmpty()) {
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = dp(12.5f)
                paint.color = p.secondaryText
                canvas.drawText(s.title, dp(12f), top + headerHeight * 0.68f, paint)
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = cell * 0.62f
            }
            val gridTop = top + sectionHeader(s)
            val firstRow = max(0, floor((-gridTop) / cell).toInt())
            val rows = ceil(s.emoji.size / columns.toDouble()).toInt()
            val lastRow = min(rows - 1, floor((gridHeight - gridTop) / cell).toInt())
            paint.color = p.keyText
            for (row in firstRow..lastRow) {
                for (col in 0 until columns) {
                    val index = row * columns + col
                    if (index >= s.emoji.size) break
                    val cx = col * cell + cell / 2f
                    val cy = gridTop + row * cell + cell / 2f
                    canvas.drawText(s.emoji[index], cx, cy + emojiBaselineShift, paint)
                }
            }
        }
        canvas.restore()
        drawBar(canvas)
    }

    private fun currentSection(): Int {
        var current = 0
        sections.forEachIndexed { i, s -> if (s.top <= offset + dp(4f)) current = i }
        return current
    }

    private fun drawBar(canvas: Canvas) {
        val p = theme.palette
        fill.color = p.toolbar
        canvas.drawRect(0f, gridHeight, width.toFloat(), height.toFloat(), fill)
        fill.color = p.divider
        canvas.drawRect(0f, gridHeight, width.toFloat(), gridHeight + dp(1f), fill)
        val current = currentSection()
        for (item in bar) {
            val r = item.rect
            val highlighted = item === pressedBar ||
                (item.kind == KIND_SECTION && item.section == current && (item.section >= 0))
            if (highlighted) {
                fill.color = if (item === pressedBar) p.keyPressed else p.specialKey
                val size = min(r.width(), r.height()) - dp(8f)
                rect.set(r.centerX() - size / 2f, r.centerY() - size / 2f, r.centerX() + size / 2f, r.centerY() + size / 2f)
                canvas.drawRoundRect(rect, dp(8f), dp(8f), fill)
            }
            paint.color = p.keyText
            paint.textSize = if (item.kind == KIND_SECTION) dp(18f) else dp(15f)
            val w = paint.measureText(item.icon)
            if (w > r.width() - dp(4f)) paint.textSize *= (r.width() - dp(4f)) / w
            canvas.drawText(item.icon, r.centerX(), r.centerY() - (paint.ascent() + paint.descent()) / 2f, paint)
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offset = scroller.currY.toFloat().coerceIn(0f, maxOffset())
            postInvalidateOnAnimation()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointer = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastY = event.y
                dragging = false
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                pressedBar = if (event.y >= gridHeight) bar.firstOrNull { it.rect.contains(event.x, event.y) } else null
                if (pressedBar?.kind == KIND_BACK) {
                    ime.controller.emojiBackspace()
                    ime.onToolbarFeedback()
                    postDelayed(backspaceRepeat, BACKSPACE_DELAY_MS)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointer)
                if (index < 0) return true
                velocity?.addMovement(event)
                val y = event.getY(index)
                if (pressedBar == null && !dragging && abs(y - downY) > touchSlop) dragging = true
                if (dragging) {
                    offset = (offset + (lastY - y)).coerceIn(0f, maxOffset())
                    invalidate()
                }
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(backspaceRepeat)
                val bar = pressedBar
                pressedBar = null
                if (bar != null) {
                    if (bar.rect.contains(event.x, event.y)) onBarTap(bar)
                } else if (dragging) {
                    velocity?.let { v ->
                        v.addMovement(event)
                        v.computeCurrentVelocity(1000, config.scaledMaximumFlingVelocity.toFloat())
                        val vy = v.getYVelocity(activePointer)
                        if (abs(vy) > config.scaledMinimumFlingVelocity) {
                            scroller.fling(0, offset.toInt(), 0, -vy.toInt(), 0, 0, 0, maxOffset().toInt())
                            postInvalidateOnAnimation()
                        }
                    }
                } else if (event.y < gridHeight) {
                    emojiAt(event.x, event.y)?.let { ime.insertEmoji(it) }
                }
                velocity?.recycle()
                velocity = null
                dragging = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(backspaceRepeat)
                pressedBar = null
                dragging = false
                velocity?.recycle()
                velocity = null
                invalidate()
            }
        }
        return true
    }

    private fun onBarTap(item: BarItem) {
        when (item.kind) {
            KIND_ABC -> ime.closePanels()
            KIND_BACK -> Unit // handled on down
            KIND_SECTION -> {
                scroller.forceFinished(true)
                offset = if (item.section < 0) 0f else sections[item.section].top.coerceAtMost(maxOffset())
                ime.onToolbarFeedback()
            }
        }
    }

    private fun emojiAt(x: Float, y: Float): String? {
        val contentY = y + offset
        val s = sections.firstOrNull { contentY >= it.top && contentY < it.top + it.height } ?: return null
        val gridY = contentY - s.top - sectionHeader(s)
        if (gridY < 0f) return null
        val index = floor(gridY / cell).toInt() * columns + floor(x / cell).toInt().coerceIn(0, columns - 1)
        return s.emoji.getOrNull(index)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(backspaceRepeat)
    }

    private companion object {
        const val RECENT_ICON = "⏱"
        const val KIND_ABC = 0
        const val KIND_SECTION = 1
        const val KIND_BACK = 2
        const val BACKSPACE_DELAY_MS = 400L
        const val BACKSPACE_REPEAT_MS = 55L
    }
}
