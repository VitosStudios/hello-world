package ch.vitosstudios.tippli.core

import ch.vitosstudios.tippli.core.geometry.Key
import ch.vitosstudios.tippli.core.geometry.KeyType
import ch.vitosstudios.tippli.core.geometry.KeyboardLayout
import ch.vitosstudios.tippli.core.geometry.LayoutBuilder
import ch.vitosstudios.tippli.core.geometry.LayoutRequest
import ch.vitosstudios.tippli.core.geometry.Page
import ch.vitosstudios.tippli.core.geometry.PixelMetrics
import ch.vitosstudios.tippli.core.geometry.Popup
import ch.vitosstudios.tippli.core.input.GestureConfig
import ch.vitosstudios.tippli.core.input.GestureListener
import ch.vitosstudios.tippli.core.input.ManualScheduler
import ch.vitosstudios.tippli.core.input.PointerInterpreter
import ch.vitosstudios.tippli.core.settings.KeyboardMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerInterpreterTest {
    private val shared = TestShared.data
    private val density = 1f
    private val layout: KeyboardLayout = LayoutBuilder.build(
        LayoutRequest(
            width = 400f,
            metrics = PixelMetrics.from(shared.theme.metrics, "medium", density),
            layouts = shared.layouts,
            keyboard = shared.keyboard("qwertz")!!,
            mode = KeyboardMode.NORMAL,
            page = Page.LETTERS,
            numberRow = false,
        ),
    )
    private val scheduler = ManualScheduler()

    /** Records every callback as a readable string. */
    private inner class Recorder : GestureListener {
        val events = mutableListOf<String>()
        var alternatives: (Key) -> List<String> = { if (it.code == "e") listOf("3", "è", "é", "ê") else emptyList() }
        var backspaceResult = 1
        var popup: Popup? = null

        override fun currentLayout() = layout
        override fun alternativesFor(key: Key) = alternatives(key)
        override fun onKeyDown(key: Key) { events += "down:${key.code}" }
        override fun onTap(key: Key, shiftHeld: Boolean) { events += "tap:${key.code}" + if (shiftHeld) "+shift" else "" }
        override fun onSwipeUp(key: Key) { events += "up:${key.code}" }
        override fun onBackspace(): Int { events += "bs"; return backspaceResult }
        override fun onBackspaceWord() { events += "bsword" }
        override fun onSwipeDeleteWord() { events += "delword" }
        override fun onSwipeRestore() { events += "restore" }
        override fun onCursorMove(delta: Int) { events += "cursor:$delta" }
        override fun onShiftLongPress() { events += "capslock" }
        override fun onPopupShown(popup: Popup) { this.popup = popup; events += "popup:${popup.items.joinToString("")}" }
        override fun onPopupChanged(popup: Popup) { this.popup = popup; events += "select:${popup.selected}" }
        override fun onPopupDismissed() { popup = null; events += "dismiss" }
        override fun onAlternativeSelected(key: Key, text: String) { events += "alt:$text" }
        override fun onPressedKeysChanged() = Unit

        fun significant() = events.filterNot { it.startsWith("down:") }
    }

    private val recorder = Recorder()
    private val interpreter = PointerInterpreter(recorder, scheduler, scheduler, GestureConfig.forDensity(density, 350))

    private fun key(code: String) = layout.keys.first { it.code == code }
    private val space get() = layout.keys.first { it.type == KeyType.SPACE }
    private val back get() = layout.keys.first { it.type == KeyType.BACK }
    private val shift get() = layout.keys.first { it.type == KeyType.SHIFT }

    private fun tap(k: Key, id: Int = 0) {
        interpreter.onDown(id, k.centerX, k.centerY)
        interpreter.onUp(id, k.centerX, k.centerY)
    }

    @Test
    fun simpleTapAndForgivingDrift() {
        tap(key("a"))
        val g = key("g")
        interpreter.onDown(1, g.centerX, g.centerY)
        interpreter.onMove(1, g.centerX + 8f, g.centerY + 5f)
        interpreter.onUp(1, g.centerX + 8f, g.centerY + 5f)
        assertEquals(listOf("down:a", "tap:a", "down:g", "tap:g"), recorder.events)
    }

    @Test
    fun swipeUpTypesUppercase() {
        val k = key("h")
        interpreter.onDown(0, k.centerX, k.centerY)
        interpreter.onMove(0, k.centerX + 3f, k.centerY - 20f)
        interpreter.onUp(0, k.centerX + 3f, k.centerY - 30f)
        assertEquals(listOf("up:h"), recorder.significant())
        // Too short → normal tap.
        recorder.events.clear()
        interpreter.onDown(1, k.centerX, k.centerY)
        interpreter.onUp(1, k.centerX, k.centerY - 20f)
        assertEquals(listOf("tap:h"), recorder.significant())
    }

    @Test
    fun swipeLeftDeletesWordsInSteps() {
        val k = key("l")
        interpreter.onDown(0, k.centerX, k.centerY)
        interpreter.onMove(0, k.centerX - 30f, k.centerY)
        assertEquals(emptyList<String>(), recorder.significant())
        interpreter.onMove(0, k.centerX - 41f, k.centerY + 5f)
        assertEquals(listOf("delword"), recorder.significant())
        interpreter.onMove(0, k.centerX - 79f, k.centerY)
        assertEquals(1, recorder.significant().size)
        interpreter.onMove(0, k.centerX - 81f, k.centerY)
        interpreter.onMove(0, k.centerX - 125f, k.centerY)
        interpreter.onUp(0, k.centerX - 125f, k.centerY)
        assertEquals(listOf("delword", "delword", "delword"), recorder.significant())
    }

    @Test
    fun swipeMustBeMostlyHorizontal() {
        val k = key("l")
        interpreter.onDown(0, k.centerX, k.centerY)
        interpreter.onMove(0, k.centerX - 45f, k.centerY + 35f)
        interpreter.onUp(0, k.centerX - 45f, k.centerY + 35f)
        assertEquals(listOf("tap:l"), recorder.significant())
    }

    @Test
    fun swipeRightRestoresOncePerGesture() {
        val k = key("s")
        interpreter.onDown(0, k.centerX, k.centerY)
        interpreter.onMove(0, k.centerX + 45f, k.centerY)
        interpreter.onMove(0, k.centerX + 120f, k.centerY)
        interpreter.onUp(0, k.centerX + 150f, k.centerY)
        assertEquals(listOf("restore"), recorder.significant())
    }

    @Test
    fun longPressOpensPopupAndSelects() {
        val e = key("e")
        interpreter.onDown(0, e.centerX, e.centerY)
        scheduler.advanceBy(349)
        assertNull(recorder.popup)
        scheduler.advanceBy(1)
        val popup = assertNotNull(recorder.popup).let { recorder.popup!! }
        assertEquals("popup:3èéê", recorder.significant().single())
        // Jitter keeps the first item; then moving to the third cell selects it.
        interpreter.onMove(0, e.centerX + 2f, e.centerY)
        assertEquals(0, recorder.popup!!.selected)
        interpreter.onMove(0, popup.cells[2].centerX, popup.cells[2].centerY)
        assertEquals(2, recorder.popup!!.selected)
        interpreter.onUp(0, popup.cells[2].centerX, popup.cells[2].centerY)
        assertEquals("alt:é", recorder.significant()[recorder.significant().size - 2])
        assertEquals("dismiss", recorder.significant().last())
        assertTrue(recorder.significant().none { it.startsWith("tap") })
    }

    @Test
    fun longPressWithoutAlternativesIsASlowTap() {
        val x = key("x")
        interpreter.onDown(0, x.centerX, x.centerY)
        scheduler.advanceBy(1000)
        interpreter.onUp(0, x.centerX, x.centerY)
        assertEquals(listOf("tap:x"), recorder.significant())
    }

    @Test
    fun popupCancelledBelowKey() {
        val e = key("e")
        interpreter.onDown(0, e.centerX, e.centerY)
        scheduler.advanceBy(400)
        interpreter.onMove(0, e.centerX, e.cell.bottom + 1.6f * layout.rowHeight)
        interpreter.onUp(0, e.centerX, e.cell.bottom + 1.6f * layout.rowHeight)
        assertTrue(recorder.significant().none { it.startsWith("alt") })
        assertEquals("dismiss", recorder.significant().last())
    }

    @Test
    fun movingBeforeLongPressCancelsTimer() {
        val e = key("e")
        interpreter.onDown(0, e.centerX, e.centerY)
        interpreter.onMove(0, e.centerX, e.centerY - 15f)
        scheduler.advanceBy(1000)
        assertNull(recorder.popup)
        interpreter.onUp(0, e.centerX, e.centerY - 15f)
        assertEquals(listOf("tap:e"), recorder.significant())
    }

    @Test
    fun backspaceRepeatSwitchesToWords() {
        interpreter.onDown(0, back.centerX, back.centerY)
        assertEquals(listOf("bs"), recorder.significant())
        scheduler.advanceBy(399)
        assertEquals(1, recorder.significant().size)
        scheduler.advanceBy(1) // repeat starts after 400 ms
        assertEquals(2, recorder.significant().size)
        scheduler.advanceBy(55L * 10) // 12 characters deleted after 10 more intervals
        assertEquals(12, recorder.significant().count { it == "bs" })
        scheduler.advanceBy(55)
        assertEquals("bsword", recorder.significant().last())
        scheduler.advanceBy(219)
        assertEquals(1, recorder.significant().count { it == "bsword" })
        scheduler.advanceBy(1)
        assertEquals(2, recorder.significant().count { it == "bsword" })
        interpreter.onUp(0, back.centerX, back.centerY)
        scheduler.advanceBy(2000)
        assertEquals(2, recorder.significant().count { it == "bsword" })
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun backspaceTapDeletesOnceAndSwipeLeftDeletesWords() {
        tap(back)
        assertEquals(listOf("bs"), recorder.significant())
        recorder.events.clear()
        interpreter.onDown(1, back.centerX, back.centerY)
        interpreter.onMove(1, back.centerX - 45f, back.centerY)
        interpreter.onUp(1, back.centerX - 45f, back.centerY)
        scheduler.advanceBy(1000)
        assertEquals(listOf("bs", "delword"), recorder.significant())
    }

    @Test
    fun fastTypingRuleCommitsOlderPointer() {
        val a = key("a")
        val s = key("s")
        interpreter.onDown(0, a.centerX, a.centerY)
        interpreter.onDown(1, s.centerX, s.centerY) // a is committed now
        assertEquals(listOf("tap:a"), recorder.significant())
        interpreter.onUp(0, a.centerX, a.centerY) // ignored afterwards
        interpreter.onUp(1, s.centerX, s.centerY)
        assertEquals(listOf("tap:a", "tap:s"), recorder.significant())
    }

    @Test
    fun fastTypingRuleIgnoresMovedPointersAndPopups() {
        val e = key("e")
        val s = key("s")
        // Older pointer has a popup open → not committed.
        interpreter.onDown(0, e.centerX, e.centerY)
        scheduler.advanceBy(400)
        interpreter.onDown(1, s.centerX, s.centerY)
        assertTrue(recorder.significant().none { it == "tap:e" })
        interpreter.onUp(1, s.centerX, s.centerY)
        interpreter.onUp(0, e.centerX, e.centerY)
        assertEquals(listOf("popup:3èéê", "tap:s", "alt:3", "dismiss"), recorder.significant())
    }

    @Test
    fun spaceDragMovesCursorAndNeverTypesSpace() {
        val sp = space
        interpreter.onDown(0, sp.centerX, sp.centerY)
        interpreter.onMove(0, sp.centerX + 13f, sp.centerY)
        interpreter.onMove(0, sp.centerX + 35f, sp.centerY)
        interpreter.onMove(0, sp.centerX - 21f, sp.centerY)
        interpreter.onUp(0, sp.centerX - 21f, sp.centerY)
        assertEquals(listOf("cursor:1", "cursor:2", "cursor:-5"), recorder.significant())
    }

    @Test
    fun shiftLongPressAndChord() {
        interpreter.onDown(0, shift.centerX, shift.centerY)
        scheduler.advanceBy(350)
        interpreter.onUp(0, shift.centerX, shift.centerY)
        assertEquals(listOf("capslock"), recorder.significant())
        recorder.events.clear()
        // Hold shift and tap a letter: the letter is typed shifted, shift up does nothing.
        val k = key("k")
        interpreter.onDown(0, shift.centerX, shift.centerY)
        interpreter.onDown(1, k.centerX, k.centerY)
        interpreter.onUp(1, k.centerX, k.centerY)
        interpreter.onUp(0, shift.centerX, shift.centerY)
        assertEquals(listOf("tap:k+shift"), recorder.significant())
        recorder.events.clear()
        tap(shift)
        assertEquals(listOf("tap:\$shift"), recorder.significant())
    }

    @Test
    fun cancelForgetsPointer() {
        val k = key("k")
        interpreter.onDown(0, k.centerX, k.centerY)
        interpreter.onCancel(0)
        interpreter.onUp(0, k.centerX, k.centerY)
        assertEquals(emptyList<String>(), recorder.significant())
        assertTrue(interpreter.pressedKeys().isEmpty())
    }

    @Test
    fun toolbarTouchesAreIgnored() {
        interpreter.onDown(0, 100f, 10f)
        interpreter.onUp(0, 100f, 10f)
        assertTrue(recorder.events.isEmpty())
    }
}
