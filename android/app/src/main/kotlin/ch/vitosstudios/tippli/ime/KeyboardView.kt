package ch.vitosstudios.tippli.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import ch.vitosstudios.tippli.core.engine.AiStatus
import ch.vitosstudios.tippli.core.geometry.Key
import ch.vitosstudios.tippli.core.geometry.KeyShape
import ch.vitosstudios.tippli.core.geometry.KeyType
import ch.vitosstudios.tippli.core.geometry.KeyboardLayout
import ch.vitosstudios.tippli.core.geometry.Page
import ch.vitosstudios.tippli.core.geometry.PixelMetrics
import ch.vitosstudios.tippli.core.geometry.Popup
import ch.vitosstudios.tippli.core.keyboard.ShiftState
import ch.vitosstudios.tippli.core.settings.KeyboardMode
import ch.vitosstudios.tippli.core.settings.Trigger
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The keyboard drawn on a Canvas (SPEC §3, §7): toolbar, normal and hex keys,
 * long-press popup, key preview and the language list. Touches in the key area
 * go to the platform-agnostic [ch.vitosstudios.tippli.core.input.PointerInterpreter]
 * with full multi-touch; toolbar touches are handled here.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val ime: TippliImeService) : View(context) {

    private val controller get() = ime.controller
    private val engine get() = ime.engine
    private val strings get() = ime.strings

    var theme: KeyboardTheme = ime.keyboardTheme
        set(value) {
            field = value
            hexPaintRadius = -1f
            invalidate()
        }

    /** True while the emoji or tips panel covers the key area (only the toolbar is live). */
    var keysHidden: Boolean = false
        set(value) {
            field = value
            languageMenu = null
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private var pixelMetrics: PixelMetrics? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hexFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var hexPaintRadius = -1f
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    // ------------------------------------------------------------------ toolbar model

    private enum class Tool { AI, UNDO, MOOD, CORRECT, TIPS, LANGUAGE, LAYOUT, SETTINGS, MESSAGE, MESSAGE_SETTINGS }

    private class Slot(val tool: Tool, val rect: RectF, val icon: String, val label: String?, val chip: Boolean)

    private var slots: List<Slot> = emptyList()
    private var toolbarPointer = -1
    private var pressedSlot: Slot? = null
    private var toolbarLongPressed = false
    private val toolbarLongPress = Runnable { onToolbarLongPress() }

    private var message: String? = null
    private var messageHasSettings = false
    private val hideMessage = Runnable {
        message = null
        invalidate()
    }

    /** Language list opened by a long press on the language chip (SPEC §5.9). */
    private var languageMenu: List<String>? = null
    private var languageMenuRect = RectF()

    // ------------------------------------------------------------------ measuring

    /** Pixel metrics; rows shrink when the keyboard would cover too much of the screen. */
    fun computeMetrics(): PixelMetrics {
        val settings = ime.settings
        val base = PixelMetrics.from(theme.metrics, settings.keyHeight, density)
        val total = controller.totalHeight(base)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val maxHeight = resources.displayMetrics.heightPixels * if (landscape) 0.58f else 0.5f
        if (total <= maxHeight) return base
        val fixed = base.toolbarHeight + base.bottomPadding
        val scale = ((maxHeight - fixed) / (total - fixed)).coerceIn(0.55f, 1f)
        return PixelMetrics.from(theme.metrics, settings.keyHeight, density, scale)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val metrics = computeMetrics()
        pixelMetrics = metrics
        setMeasuredDimension(width, ceil(controller.totalHeight(metrics).toDouble()).toInt())
    }

    /** Called when page, mode, language or settings changed. */
    fun onKeyboardLayoutChanged() {
        pixelMetrics = null
        languageMenu = null
        requestLayout()
        invalidate()
    }

    val toolbarHeightPx: Int get() = ((pixelMetrics ?: computeMetrics()).toolbarHeight).toInt()

    private fun currentLayout(): KeyboardLayout? {
        if (width == 0) return null
        val metrics = pixelMetrics ?: computeMetrics().also { pixelMetrics = it }
        return controller.layout(width.toFloat(), metrics)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val palette = theme.palette
        canvas.drawColor(palette.background)
        val layout = currentLayout() ?: return
        buildToolbar(layout)
        drawToolbar(canvas, layout)
        if (!keysHidden) {
            val pressed = controller.interpreter.pressedKeys()
            for (key in layout.keys) drawKey(canvas, layout, key, pressed.contains(key))
            drawPreview(canvas, layout, pressed)
            controller.popup?.let { drawPopup(canvas, layout, it) }
            languageMenu?.let { drawLanguageMenu(canvas, layout, it) }
        }
        if (engine.status == AiStatus.WORKING || controller.enterWaiting) postInvalidateDelayed(ANIMATION_FRAME_MS)
    }

    private fun keyBackground(key: Key, pressed: Boolean): Int {
        val p = theme.palette
        return when {
            key.type == KeyType.ENTER -> if (pressed) KeyboardTheme.blend(p.accent, p.keyText, 0.2f) else p.accent
            pressed -> p.keyPressed
            key.type == KeyType.CHAR -> p.key
            key.type == KeyType.SPACE -> p.spaceKey
            key.type == KeyType.SHIFT && controller.shift != ShiftState.OFF -> p.key
            else -> p.specialKey
        }
    }

    private fun drawKey(canvas: Canvas, layout: KeyboardLayout, key: Key, pressed: Boolean) {
        val p = theme.palette
        val shadow = dp(1f)
        val background = keyBackground(key, pressed)
        if (key.shape == KeyShape.HEX) {
            if (hexPaintRadius != key.cornerRadius) {
                hexPaintRadius = key.cornerRadius
                hexFill.pathEffect = CornerPathEffect(key.cornerRadius)
            }
            path.reset()
            key.hexVertices.forEachIndexed { i, v -> if (i == 0) path.moveTo(v.x, v.y) else path.lineTo(v.x, v.y) }
            path.close()
            hexFill.color = p.keyShadow
            canvas.save()
            canvas.translate(0f, shadow)
            canvas.drawPath(path, hexFill)
            canvas.restore()
            hexFill.color = background
            canvas.drawPath(path, hexFill)
        } else {
            val r = key.cornerRadius
            rect.set(key.visual.left, key.visual.top + shadow, key.visual.right, key.visual.bottom + shadow)
            fill.color = p.keyShadow
            canvas.drawRoundRect(rect, r, r, fill)
            rect.offset(0f, -shadow)
            fill.color = background
            canvas.drawRoundRect(rect, r, r, fill)
        }
        drawKeyLabel(canvas, layout, key)
    }

    private fun drawKeyLabel(canvas: Canvas, layout: KeyboardLayout, key: Key) {
        val p = theme.palette
        val m = layout.metrics
        val cx = key.centerX
        val cy = key.centerY
        val maxWidth = key.visual.width - dp(6f)
        if (key.type == KeyType.ENTER && controller.enterWaiting) {
            drawSpinner(canvas, cx, cy, min(key.visual.height, key.visual.width) * 0.22f, p.accentText)
            return
        }
        val label = controller.labelFor(key) { strings[it] }
        if (label.isEmpty()) return
        val color = when (key.type) {
            KeyType.ENTER -> p.accentText
            KeyType.SPACE -> p.secondaryText
            else -> p.keyText
        }
        val size = when (key.type) {
            KeyType.CHAR -> if (label.length <= 2) m.keyLabelSize else m.specialLabelSize
            KeyType.SHIFT, KeyType.BACK -> m.specialLabelSize * 1.4f
            KeyType.EMOJI -> m.specialLabelSize * 1.2f
            else -> m.specialLabelSize
        }
        // Labels shrink with the rows (landscape) but never grow.
        val nominalRow = theme.metrics.rowHeightFor(ime.settings.keyHeight) * density
        val scale = (layout.rowHeight / nominalRow).coerceIn(0.7f, 1f)
        drawFittedText(canvas, label, cx, cy, size * scale, maxWidth, color)
        if (key.type == KeyType.SHIFT && controller.shift == ShiftState.CAPS_LOCK) {
            line.color = p.keyText
            line.strokeWidth = dp(2f)
            val half = dp(7f)
            val y = cy + size * 0.55f
            canvas.drawLine(cx - half, y, cx + half, y, line)
        }
        controller.digitHintFor(key)?.let { digit ->
            text.textSize = m.specialLabelSize * 0.7f
            text.color = p.secondaryText
            val hx: Float
            val hy: Float
            if (key.shape == KeyShape.HEX) {
                hx = cx + key.visual.width * 0.24f
                hy = key.visual.top + key.visual.height * 0.34f
            } else {
                hx = key.visual.right - dp(7f)
                hy = key.visual.top + dp(12f)
            }
            canvas.drawText(digit, hx, hy, text)
        }
    }

    private fun drawFittedText(canvas: Canvas, s: String, cx: Float, cy: Float, size: Float, maxWidth: Float, color: Int) {
        text.textSize = size
        text.color = color
        val w = text.measureText(s)
        if (w > maxWidth && w > 0f) text.textSize = size * maxWidth / w
        val baseline = cy - (text.ascent() + text.descent()) / 2f
        canvas.drawText(s, cx, baseline, text)
    }

    private fun drawSpinner(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        line.color = color
        line.strokeWidth = dp(2.5f)
        val angle = (SystemClock.uptimeMillis() % 1000L) * 0.36f
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rect, angle, 270f, false, line)
    }

    private fun drawPreview(canvas: Canvas, layout: KeyboardLayout, pressed: List<Key>) {
        if (layout.geometryMode != KeyboardMode.NORMAL || layout.page != Page.LETTERS || controller.popup != null) return
        val p = theme.palette
        for (key in pressed) {
            if (key.type != KeyType.CHAR) continue
            val w = max(key.visual.width * 1.35f, dp(40f))
            val h = layout.rowHeight * 1.1f
            var top = key.visual.top - h - dp(2f)
            if (top < 0f) top = 0f
            rect.set(key.centerX - w / 2f, top, key.centerX + w / 2f, top + h)
            if (rect.left < 0f) rect.offset(-rect.left, 0f)
            if (rect.right > width) rect.offset(width - rect.right, 0f)
            val r = layout.metrics.keyCornerRadius * 1.4f
            fill.color = p.keyShadow
            rect.offset(0f, dp(1.5f))
            canvas.drawRoundRect(rect, r, r, fill)
            rect.offset(0f, -dp(1.5f))
            fill.color = p.popup
            canvas.drawRoundRect(rect, r, r, fill)
            drawFittedText(
                canvas, controller.labelFor(key) { strings[it] }, rect.centerX(), rect.centerY(),
                layout.metrics.keyLabelSize * 1.3f, rect.width() - dp(4f), p.keyText,
            )
        }
    }

    private fun drawPopup(canvas: Canvas, layout: KeyboardLayout, popup: Popup) {
        val p = theme.palette
        val r = layout.metrics.keyCornerRadius
        val b = popup.bounds
        rect.set(b.left - dp(3f), b.top - dp(3f), b.right + dp(3f), b.bottom + dp(3f))
        fill.color = p.keyShadow
        rect.offset(0f, dp(2f))
        canvas.drawRoundRect(rect, r, r, fill)
        rect.offset(0f, -dp(2f))
        fill.color = p.popup
        canvas.drawRoundRect(rect, r, r, fill)
        popup.items.forEachIndexed { i, item ->
            val cell = popup.cells[i]
            val selected = i == popup.selected
            if (selected) {
                rect.set(cell.left + dp(1.5f), cell.top + dp(1.5f), cell.right - dp(1.5f), cell.bottom - dp(1.5f))
                fill.color = p.popupSelected
                canvas.drawRoundRect(rect, r, r, fill)
            }
            drawFittedText(
                canvas, item, cell.centerX, cell.centerY, layout.metrics.keyLabelSize * 0.9f,
                cell.width - dp(4f), if (selected) p.accentText else p.keyText,
            )
        }
    }

    private fun drawLanguageMenu(canvas: Canvas, layout: KeyboardLayout, ids: List<String>) {
        val p = theme.palette
        val itemHeight = dp(40f)
        val w = min(width * 0.7f, dp(260f))
        val top = layout.toolbarHeight + dp(2f)
        val h = min(itemHeight * ids.size, height - top - dp(4f))
        languageMenuRect.set(width - w - dp(6f), top, width - dp(6f), top + h)
        val r = layout.metrics.keyCornerRadius
        rect.set(languageMenuRect)
        rect.offset(0f, dp(2f))
        fill.color = p.keyShadow
        canvas.drawRoundRect(rect, r, r, fill)
        fill.color = p.popup
        canvas.drawRoundRect(languageMenuRect, r, r, fill)
        text.textAlign = Paint.Align.LEFT
        ids.forEachIndexed { i, id ->
            val y = top + i * itemHeight
            if (y + itemHeight > languageMenuRect.bottom + 1f) return@forEachIndexed
            val language = ime.app.shared.language(id) ?: return@forEachIndexed
            val active = id == controller.language.id
            if (active) {
                rect.set(languageMenuRect.left + dp(4f), y + dp(3f), languageMenuRect.right - dp(4f), y + itemHeight - dp(3f))
                fill.color = p.popupSelected
                canvas.drawRoundRect(rect, r, r, fill)
            }
            text.textSize = dp(15f)
            text.color = if (active) p.accentText else p.keyText
            val baseline = y + itemHeight / 2f - (text.ascent() + text.descent()) / 2f
            canvas.drawText(ellipsize("${language.short}  ·  ${language.name}", w - dp(28f)), languageMenuRect.left + dp(14f), baseline, text)
        }
        text.textAlign = Paint.Align.CENTER
    }

    private fun ellipsize(s: String, maxWidth: Float): String {
        if (text.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && text.measureText(s, 0, end) + text.measureText("…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }

    // ------------------------------------------------------------------ toolbar

    private fun buildToolbar(layout: KeyboardLayout) {
        val h = layout.toolbarHeight
        val pad = dp(6f)
        val chipPad = dp(10f)
        val gap = dp(2f)
        text.textSize = dp(14f)
        val result = ArrayList<Slot>()

        message?.let { msg ->
            val settingsLabel = "⚙︎ " + strings["toolbar.settings"]
            val settingsWidth = if (messageHasSettings) text.measureText(settingsLabel) + 2 * chipPad else 0f
            if (messageHasSettings) {
                result += Slot(Tool.MESSAGE_SETTINGS, RectF(width - pad - settingsWidth, dp(5f), width - pad, h - dp(5f)), settingsLabel, null, true)
            }
            result += Slot(Tool.MESSAGE, RectF(pad, 0f, width - pad - settingsWidth - gap, h), msg, null, false)
            slots = result
            return
        }

        val provider = ime.providerShortName
        val iconW = dp(40f)
        fun chipWidth(icon: String, label: String?) =
            2 * chipPad + text.measureText(icon) + (label?.let { dp(5f) + text.measureText(it) } ?: 0f)

        val right = ArrayList<Pair<Tool, Pair<String, String?>>>()
        val manual = ime.settings.trigger == Trigger.MANUAL
        if (manual) right += Tool.CORRECT to ("✨" to null)
        right += Tool.TIPS to ("💡" to strings["toolbar.tips"])
        right += Tool.LANGUAGE to (controller.language.short to null)
        right += Tool.LAYOUT to ((if (controller.mode == KeyboardMode.HEX) "▭" else "⬡") to null)
        right += Tool.SETTINGS to ("⚙︎" to null)

        val left = ArrayList<Pair<Tool, Pair<String, String?>>>()
        left += Tool.AI to ("●" to provider)
        if (engine.canUndo) left += Tool.UNDO to ("↶" to strings["toolbar.undo"])
        if (engine.moodLevel >= 2) left += Tool.MOOD to ("⚠︎" to strings["toolbar.emotionChip"])

        fun widthOf(tool: Tool, icon: String, label: String?, compact: Boolean): Float = when (tool) {
            Tool.CORRECT, Tool.LAYOUT, Tool.SETTINGS -> iconW
            Tool.AI -> dp(26f) + if (compact) 0f else text.measureText(label.orEmpty()) + dp(6f)
            else -> chipWidth(icon, if (compact && tool != Tool.LANGUAGE) null else label)
        }

        var compact = false
        val total = { c: Boolean ->
            (left + right).sumOf { (t, il) -> widthOf(t, il.first, il.second, c).toDouble() }.toFloat() +
                gap * (left.size + right.size) + 2 * pad
        }
        if (total(false) > width) compact = true

        var x = pad
        for ((tool, il) in left) {
            val w = widthOf(tool, il.first, il.second, compact)
            val label = if (compact && tool != Tool.LANGUAGE) null else il.second
            result += Slot(tool, RectF(x, dp(5f), x + w, h - dp(5f)), il.first, label, tool != Tool.AI)
            x += w + gap
        }
        var xr = width - pad
        for ((tool, il) in right.asReversed()) {
            val w = widthOf(tool, il.first, il.second, compact)
            val label = if (compact && tool != Tool.LANGUAGE) null else il.second
            val chip = tool == Tool.TIPS || tool == Tool.LANGUAGE
            result += Slot(tool, RectF(xr - w, dp(5f), xr, h - dp(5f)), il.first, label, chip)
            xr -= w + gap
        }
        slots = result
    }

    private fun drawToolbar(canvas: Canvas, layout: KeyboardLayout) {
        val p = theme.palette
        fill.color = p.toolbar
        canvas.drawRect(0f, 0f, width.toFloat(), layout.toolbarHeight, fill)
        text.textSize = dp(14f)
        for (slot in slots) {
            val pressed = slot === pressedSlot
            val r = slot.rect
            val radius = r.height() / 2f
            when (slot.tool) {
                Tool.MESSAGE -> {
                    text.textAlign = Paint.Align.LEFT
                    text.color = p.keyText
                    text.textSize = dp(13.5f)
                    val baseline = r.centerY() - (text.ascent() + text.descent()) / 2f
                    canvas.drawText(ellipsize(slot.icon, r.width() - dp(8f)), r.left + dp(4f), baseline, text)
                    text.textAlign = Paint.Align.CENTER
                    text.textSize = dp(14f)
                }
                Tool.AI -> drawAiSlot(canvas, slot, pressed)
                else -> {
                    var foreground = p.keyText
                    if (slot.chip || slot.tool == Tool.MOOD || slot.tool == Tool.UNDO || slot.tool == Tool.MESSAGE_SETTINGS) {
                        fill.color = when {
                            slot.tool == Tool.MOOD -> if (engine.moodLevel >= 3) p.danger else p.warning
                            slot.tool == Tool.MESSAGE_SETTINGS -> p.accent
                            pressed -> p.keyPressed
                            else -> p.specialKey
                        }
                        if (slot.tool == Tool.MOOD || slot.tool == Tool.MESSAGE_SETTINGS) foreground = p.accentText
                        canvas.drawRoundRect(r, radius, radius, fill)
                    } else if (pressed) {
                        fill.color = p.keyPressed
                        canvas.drawRoundRect(r, radius, radius, fill)
                    }
                    text.color = foreground
                    if (slot.label == null) {
                        drawFittedText(canvas, slot.icon, r.centerX(), r.centerY(), dp(if (slot.chip) 14f else 18f), r.width(), foreground)
                    } else {
                        text.textSize = dp(14f)
                        val iconW = text.measureText(slot.icon)
                        val all = iconW + dp(5f) + text.measureText(slot.label)
                        val startX = r.centerX() - all / 2f
                        val baseline = r.centerY() - (text.ascent() + text.descent()) / 2f
                        text.textAlign = Paint.Align.LEFT
                        canvas.drawText(slot.icon, startX, baseline, text)
                        canvas.drawText(slot.label, startX + iconW + dp(5f), baseline, text)
                        text.textAlign = Paint.Align.CENTER
                    }
                    text.textSize = dp(14f)
                }
            }
        }
    }

    private fun drawAiSlot(canvas: Canvas, slot: Slot, pressed: Boolean) {
        val p = theme.palette
        val r = slot.rect
        if (pressed) {
            fill.color = p.keyPressed
            canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
        }
        val status = engine.status
        val cx = r.left + dp(13f)
        val cy = r.centerY()
        if (status == AiStatus.LOCKED) {
            drawFittedText(canvas, "🔒", cx, cy, dp(13f), dp(20f), p.secondaryText)
        } else {
            val color = when (status) {
                AiStatus.READY, AiStatus.WORKING -> p.accent
                AiStatus.ERROR -> p.danger
                AiStatus.OFF, AiStatus.NO_KEY, AiStatus.LOCKED -> p.secondaryText
            }
            fill.color = color
            var radius = dp(5f)
            if (status == AiStatus.WORKING) {
                val phase = (SystemClock.uptimeMillis() % 900L) / 900.0
                val pulse = (0.5 + 0.5 * sin(phase * 2 * Math.PI)).toFloat()
                fill.color = KeyboardTheme.withAlpha(color, (110 + 145 * pulse).toInt())
                radius = dp(4f + 2f * pulse)
            }
            canvas.drawCircle(cx, cy, radius, fill)
        }
        slot.label?.let { label ->
            text.textAlign = Paint.Align.LEFT
            text.textSize = dp(14f)
            text.color = if (status == AiStatus.READY || status == AiStatus.WORKING) p.keyText else p.secondaryText
            val baseline = cy - (text.ascent() + text.descent()) / 2f
            canvas.drawText(label, r.left + dp(24f), baseline, text)
            text.textAlign = Paint.Align.CENTER
        }
    }

    /** Shows a short message in the toolbar ("toast"), optionally with a settings button (SPEC §6.7). */
    fun showMessage(text: String, withSettings: Boolean, durationMs: Long = 4_000L) {
        message = text
        messageHasSettings = withSettings
        removeCallbacks(hideMessage)
        postDelayed(hideMessage, durationMs)
        invalidate()
    }

    private fun slotAt(x: Float, y: Float): Slot? = slots.firstOrNull { it.rect.contains(x, y) }
        ?: slots.filter { y < (pixelMetrics?.toolbarHeight ?: 0f) }
            .minByOrNull { if (x < it.rect.left) it.rect.left - x else if (x > it.rect.right) x - it.rect.right else 0f }
            ?.takeIf { s -> x >= s.rect.left - dp(8f) && x <= s.rect.right + dp(8f) }

    private fun onToolbarDown(id: Int, x: Float, y: Float) {
        val slot = slotAt(x, y) ?: return
        toolbarPointer = id
        pressedSlot = slot
        toolbarLongPressed = false
        if (slot.tool == Tool.AI || slot.tool == Tool.LANGUAGE) postDelayed(toolbarLongPress, TOOLBAR_LONG_PRESS_MS)
        ime.onToolbarFeedback()
        invalidate()
    }

    private fun onToolbarUp(x: Float, y: Float) {
        removeCallbacks(toolbarLongPress)
        val slot = pressedSlot
        pressedSlot = null
        toolbarPointer = -1
        invalidate()
        if (slot == null || toolbarLongPressed) return
        val inside = slot.rect.left - dp(12f) <= x && x <= slot.rect.right + dp(12f) && y <= (pixelMetrics?.toolbarHeight ?: 0f) + dp(16f)
        if (!inside) return
        onToolbarTap(slot.tool)
    }

    private fun onToolbarTap(tool: Tool) {
        when (tool) {
            Tool.AI -> when (engine.status) {
                AiStatus.ERROR -> {
                    val error = engine.lastError
                    showMessage(strings[error?.kind?.stringPath ?: "errors.server"], withSettings = true)
                }
                AiStatus.NO_KEY -> showMessage(strings["errors.noKey"], withSettings = true)
                AiStatus.LOCKED -> showMessage(strings["toolbar.aiLocked"], withSettings = false, durationMs = 2_500L)
                else -> {
                    engine.toggleSession()
                    showMessage(
                        strings[if (engine.sessionEnabled) "toolbar.aiOn" else "toolbar.aiOff"],
                        withSettings = false,
                        durationMs = 1_200L,
                    )
                }
            }
            Tool.UNDO -> ime.undoCorrection()
            Tool.MOOD -> ime.openTips(scrollToEmotion = true)
            Tool.CORRECT -> engine.correctNow()
            Tool.TIPS -> ime.toggleTips()
            Tool.LANGUAGE -> controller.cycleLanguage()
            Tool.LAYOUT -> controller.toggleKeyboardMode()
            Tool.SETTINGS, Tool.MESSAGE_SETTINGS -> ime.openSettings()
            Tool.MESSAGE -> {
                removeCallbacks(hideMessage)
                message = null
            }
        }
        invalidate()
    }

    private fun onToolbarLongPress() {
        val slot = pressedSlot ?: return
        toolbarLongPressed = true
        when (slot.tool) {
            Tool.AI -> {
                // ✨ "correct now" lives behind a long press on the AI dot (SPEC §7.1).
                ime.onLongPressFeedback()
                if (engine.status == AiStatus.READY || engine.status == AiStatus.ERROR) {
                    engine.correctNow()
                    showMessage(strings["toolbar.correctNow"], withSettings = false, durationMs = 1_200L)
                }
            }
            Tool.LANGUAGE -> {
                ime.onLongPressFeedback()
                val languages = ime.settings.languages
                if (!keysHidden && languages.isNotEmpty()) languageMenu = languages
            }
            else -> Unit
        }
        pressedSlot = null
        invalidate()
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolbarBottom = (pixelMetrics ?: computeMetrics()).toolbarHeight
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)
                val menu = languageMenu
                if (menu != null) {
                    onLanguageMenuTouch(menu, x, y)
                    return true
                }
                if (y < toolbarBottom && toolbarPointer == -1 && controller.popup == null) {
                    onToolbarDown(id, x, y)
                } else if (!keysHidden) {
                    controller.interpreter.onDown(id, x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    if (id == toolbarPointer) continue
                    if (!keysHidden) controller.interpreter.onMove(id, event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                if (id == toolbarPointer) {
                    onToolbarUp(event.getX(index), event.getY(index))
                } else if (!keysHidden) {
                    controller.interpreter.onUp(id, event.getX(index), event.getY(index))
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(toolbarLongPress)
                pressedSlot = null
                toolbarPointer = -1
                controller.interpreter.cancelAll()
            }
        }
        invalidate()
        return true
    }

    private fun onLanguageMenuTouch(ids: List<String>, x: Float, y: Float) {
        languageMenu = null
        if (languageMenuRect.contains(x, y)) {
            val index = ((y - languageMenuRect.top) / dp(40f)).toInt()
            ids.getOrNull(index)?.let { controller.selectLanguage(it) }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(toolbarLongPress)
        removeCallbacks(hideMessage)
    }

    private companion object {
        const val ANIMATION_FRAME_MS = 40L
        const val TOOLBAR_LONG_PRESS_MS = 450L
    }
}
