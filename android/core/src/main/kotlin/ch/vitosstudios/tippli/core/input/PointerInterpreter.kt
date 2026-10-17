package ch.vitosstudios.tippli.core.input

import ch.vitosstudios.tippli.core.geometry.Key
import ch.vitosstudios.tippli.core.geometry.KeyType
import ch.vitosstudios.tippli.core.geometry.KeyboardLayout
import ch.vitosstudios.tippli.core.geometry.Popup
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/** Gesture thresholds of SPEC §4.1 in pixels/milliseconds. */
data class GestureConfig(
    val tapSlop: Float,
    val swipeUpMin: Float,
    val swipeHMin: Float,
    val swipeHStep: Float,
    val cursorStep: Float,
    val longPressMs: Long = 350,
    val repeatDelayMs: Long = 400,
    val repeatIntervalMs: Long = 55,
    val wordRepeatAfter: Int = 12,
    val wordRepeatIntervalMs: Long = 220,
) {
    companion object {
        /** Builds the thresholds from dp values and the display density. */
        fun forDensity(density: Float, longPressMs: Long = 350) = GestureConfig(
            tapSlop = 12f * density,
            swipeUpMin = 28f * density,
            swipeHMin = 40f * density,
            swipeHStep = 40f * density,
            cursorStep = 10f * density,
            longPressMs = longPressMs,
        )
    }
}

/**
 * What the pointer interpreter reports. Implemented by the keyboard
 * controller; the methods are called on the UI thread.
 */
interface GestureListener {
    /** Current geometry used for hit testing. */
    fun currentLayout(): KeyboardLayout?

    /** Long-press alternatives for [key], already upper-cased when shift is active (SPEC §4.4). */
    fun alternativesFor(key: Key): List<String>

    /** A pointer went down on [key]: show pressed state, haptic/sound feedback. */
    fun onKeyDown(key: Key)

    /** Normal tap. [shiftHeld] is true while another pointer holds `$shift` (chord). */
    fun onTap(key: Key, shiftHeld: Boolean)

    /** Swipe up on a character key: type it upper-case (SPEC §4.1). */
    fun onSwipeUp(key: Key)

    /** Backspace down / repeat: delete one grapheme. Returns the number of characters removed (0 or 1). */
    fun onBackspace(): Int

    /** Backspace repeat in word mode. */
    fun onBackspaceWord()

    /** Swipe left: delete one word and push it to the restore stack. */
    fun onSwipeDeleteWord()

    /** Swipe right: restore / undo (SPEC §4.6). */
    fun onSwipeRestore()

    /** Space-bar trackpad: move the cursor by [delta] characters. */
    fun onCursorMove(delta: Int)

    /** Long press on shift → caps lock. */
    fun onShiftLongPress()

    fun onPopupShown(popup: Popup)
    fun onPopupChanged(popup: Popup)
    fun onPopupDismissed()

    /** The user released the pointer on an alternative of the popup. */
    fun onAlternativeSelected(key: Key, text: String)

    /** Pressed keys changed (repaint). */
    fun onPressedKeysChanged()
}

/**
 * Platform-agnostic multi-touch gesture interpreter (SPEC §4.1–4.6).
 * The platform feeds raw pointer events in pixels; all decisions (tap, swipe,
 * long press, cursor mode, backspace repeat, fast typing rule) are made here.
 */
class PointerInterpreter(
    private val listener: GestureListener,
    private val scheduler: Scheduler,
    private val clock: Clock,
    var config: GestureConfig,
) {
    private enum class Mode { PENDING, POPUP, CURSOR, SWIPE_LEFT, SWIPE_RIGHT, REPEAT, CONSUMED, DONE }

    private inner class PointerState(val id: Int, val key: Key, val downX: Float, val downY: Float) {
        var mode = Mode.PENDING
        var movedBeyondSlop = false
        var timer: Cancellable? = null
        var deleted = 0
        var wordMode = false
        var swipeSteps = 0
        var cursorAnchorX = downX
        var popup: Popup? = null
        var popupOriginX = 0f
        var popupOriginY = 0f
        var popupTracking = false
        var chorded = false

        fun cancelTimer() {
            timer?.cancel()
            timer = null
        }
    }

    private val pointers = LinkedHashMap<Int, PointerState>()

    /** Keys currently shown as pressed. */
    fun pressedKeys(): List<Key> = pointers.values.filter { it.mode != Mode.DONE }.map { it.key }

    /** The open long-press popup, if any. */
    fun activePopup(): Popup? = pointers.values.firstOrNull { it.mode == Mode.POPUP }?.popup

    fun hasActivePointers(): Boolean = pointers.values.any { it.mode != Mode.DONE }

    fun onDown(id: Int, x: Float, y: Float) {
        pointers.remove(id)?.cancelTimer()
        val layout = listener.currentLayout() ?: return
        val key = layout.hitTest(x, y) ?: return

        // Fast typing rule (SPEC §4.1): commit older character pointers immediately.
        for (other in pointers.values.toList()) {
            if (other.mode == Mode.PENDING && other.key.type.typesCharacter && !other.movedBeyondSlop && other.popup == null) {
                other.cancelTimer()
                other.mode = Mode.DONE
                listener.onTap(other.key, shiftHeld = isShiftHeld(except = other))
                markShiftChord(except = other)
            }
        }

        val p = PointerState(id, key, x, y)
        pointers[id] = p
        listener.onKeyDown(key)

        when (key.type) {
            KeyType.BACK -> {
                p.deleted += listener.onBackspace()
                p.timer = scheduler.schedule(config.repeatDelayMs) { startRepeat(p) }
            }
            KeyType.SHIFT -> {
                p.timer = scheduler.schedule(config.longPressMs) {
                    if (p.mode == Mode.PENDING && pointers[p.id] === p) {
                        p.mode = Mode.CONSUMED
                        listener.onShiftLongPress()
                    }
                }
            }
            KeyType.CHAR -> {
                if (listener.alternativesFor(key).isNotEmpty()) {
                    p.timer = scheduler.schedule(config.longPressMs) { openPopup(p) }
                }
            }
            else -> Unit
        }
        listener.onPressedKeysChanged()
    }

    fun onMove(id: Int, x: Float, y: Float) {
        val p = pointers[id] ?: return
        if (p.mode == Mode.DONE || p.mode == Mode.CONSUMED) return
        val dx = x - p.downX
        val dy = y - p.downY
        if (!p.movedBeyondSlop && hypot(dx, dy) > config.tapSlop) {
            p.movedBeyondSlop = true
            // Cancels the long-press timer (and the not yet started backspace repeat).
            if (p.mode == Mode.PENDING) p.cancelTimer()
        }
        when (p.mode) {
            Mode.POPUP -> updatePopup(p, x, y)
            Mode.CURSOR -> moveCursor(p, x)
            Mode.SWIPE_LEFT -> swipeLeft(p, dx)
            Mode.SWIPE_RIGHT, Mode.DONE, Mode.CONSUMED -> Unit
            Mode.PENDING, Mode.REPEAT -> {
                if (p.key.type == KeyType.SPACE) {
                    if (abs(dx) > config.tapSlop) {
                        p.cancelTimer()
                        p.mode = Mode.CURSOR
                        p.cursorAnchorX = p.downX
                        moveCursor(p, x)
                    }
                } else if (abs(dx) >= config.swipeHMin && abs(dx) > 1.5f * abs(dy)) {
                    p.cancelTimer()
                    if (dx < 0) {
                        p.mode = Mode.SWIPE_LEFT
                        swipeLeft(p, dx)
                    } else {
                        p.mode = Mode.SWIPE_RIGHT
                        listener.onSwipeRestore()
                    }
                    listener.onPressedKeysChanged()
                }
            }
        }
    }

    fun onUp(id: Int, x: Float, y: Float) {
        val p = pointers.remove(id) ?: return
        p.cancelTimer()
        val dx = x - p.downX
        val dy = y - p.downY
        when (p.mode) {
            Mode.DONE -> Unit
            Mode.POPUP -> {
                val popup = p.popup
                if (popup != null && popup.selected in popup.items.indices) {
                    listener.onAlternativeSelected(p.key, popup.items[popup.selected])
                }
                listener.onPopupDismissed()
            }
            Mode.CURSOR, Mode.SWIPE_LEFT, Mode.SWIPE_RIGHT, Mode.REPEAT, Mode.CONSUMED -> Unit
            Mode.PENDING -> when {
                p.key.type == KeyType.BACK -> Unit // already deleted on down
                p.key.type == KeyType.SHIFT && p.chorded -> Unit // shift was used as a modifier
                p.key.type == KeyType.CHAR && dy <= -config.swipeUpMin && abs(dy) > abs(dx) -> listener.onSwipeUp(p.key)
                else -> {
                    val shiftHeld = isShiftHeld(except = p)
                    listener.onTap(p.key, shiftHeld)
                    if (p.key.type.typesCharacter) markShiftChord(except = p)
                }
            }
        }
        listener.onPressedKeysChanged()
    }

    /** Pointer cancelled by the system: forget it without typing anything. */
    fun onCancel(id: Int) {
        val p = pointers.remove(id) ?: return
        p.cancelTimer()
        if (p.mode == Mode.POPUP) listener.onPopupDismissed()
        listener.onPressedKeysChanged()
    }

    /** Forget all pointers (keyboard hidden, layout changed). */
    fun cancelAll() {
        val hadPopup = pointers.values.any { it.mode == Mode.POPUP }
        pointers.values.forEach { it.cancelTimer() }
        pointers.clear()
        if (hadPopup) listener.onPopupDismissed()
        listener.onPressedKeysChanged()
    }

    private fun isShiftHeld(except: PointerState): Boolean =
        pointers.values.any { it !== except && it.key.type == KeyType.SHIFT && it.mode == Mode.PENDING }

    private fun markShiftChord(except: PointerState) {
        pointers.values.filter { it !== except && it.key.type == KeyType.SHIFT && it.mode == Mode.PENDING }
            .forEach {
                it.chorded = true
                it.cancelTimer()
            }
    }

    private fun openPopup(p: PointerState) {
        if (p.mode != Mode.PENDING || pointers[p.id] !== p || p.movedBeyondSlop) return
        val items = listener.alternativesFor(p.key)
        val layout = listener.currentLayout() ?: return
        if (items.isEmpty()) return
        val popup = layout.popupFor(p.key, items)
        p.popup = popup
        p.mode = Mode.POPUP
        p.popupTracking = false
        p.popupOriginX = p.downX
        p.popupOriginY = p.downY
        listener.onPopupShown(popup)
    }

    private fun updatePopup(p: PointerState, x: Float, y: Float) {
        val popup = p.popup ?: return
        val layout = listener.currentLayout() ?: return
        // Ignore finger jitter right after the popup opened; the first item stays selected.
        if (!p.popupTracking) {
            if (hypot(x - p.popupOriginX, y - p.popupOriginY) <= config.tapSlop / 2f) return
            p.popupTracking = true
        }
        val index = popup.indexAt(x, y, layout.rowHeight)
        if (index != popup.selected) {
            val updated = popup.copy(selected = index)
            p.popup = updated
            listener.onPopupChanged(updated)
        }
    }

    private fun moveCursor(p: PointerState, x: Float) {
        val steps = ((x - p.cursorAnchorX) / config.cursorStep).toInt() // truncates towards zero
        if (steps != 0) {
            p.cursorAnchorX += steps * config.cursorStep
            listener.onCursorMove(steps)
        }
    }

    private fun swipeLeft(p: PointerState, dx: Float) {
        val travel = -dx
        if (travel < config.swipeHMin) return
        val wanted = 1 + floor((travel - config.swipeHMin) / config.swipeHStep).toInt()
        while (p.swipeSteps < wanted) {
            p.swipeSteps++
            listener.onSwipeDeleteWord()
        }
    }

    private fun startRepeat(p: PointerState) {
        if (pointers[p.id] !== p || p.mode != Mode.PENDING) return
        p.mode = Mode.REPEAT
        repeatStep(p)
    }

    private fun repeatStep(p: PointerState) {
        if (pointers[p.id] !== p || p.mode != Mode.REPEAT) return
        if (!p.wordMode && p.deleted >= config.wordRepeatAfter) p.wordMode = true
        val interval: Long
        if (p.wordMode) {
            listener.onBackspaceWord()
            interval = config.wordRepeatIntervalMs
        } else {
            p.deleted += listener.onBackspace()
            interval = config.repeatIntervalMs
        }
        p.timer = scheduler.schedule(interval) { repeatStep(p) }
    }
}
