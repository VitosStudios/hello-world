package com.vitosstudios.suite.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UndoManagerTest {
    private class Counter { var value = 0 }

    private fun UndoManager.add(c: Counter, n: Int) {
        c.value += n
        record(LambdaChange({ c.value -= n }, { c.value += n }))
    }

    @Test
    fun unlimitedUndoRedo() {
        val u = UndoManager()
        val c = Counter()
        repeat(10_000) { i -> u.transaction("step $i") { u.add(c, 1) } }
        assertEquals(10_000, c.value)
        while (u.canUndo) u.undo()
        assertEquals(0, c.value)
        while (u.canRedo) u.redo()
        assertEquals(10_000, c.value)
    }

    @Test
    fun newEditClearsRedo() {
        val u = UndoManager()
        val c = Counter()
        u.transaction("a") { u.add(c, 1) }
        u.transaction("b") { u.add(c, 2) }
        u.undo()
        u.transaction("c") { u.add(c, 5) }
        assertFalse(u.canRedo)
        assertEquals(6, c.value)
    }

    @Test
    fun failedTransactionIsRolledBack() {
        val u = UndoManager()
        val c = Counter()
        assertFailsWith<IllegalStateException> {
            u.transaction("bad") { u.add(c, 3); u.add(c, 4); error("boom") }
        }
        assertEquals(0, c.value)
        assertFalse(u.canUndo)
    }

    @Test
    fun nestedTransactionsFormOneStep() {
        val u = UndoManager()
        val c = Counter()
        u.transaction("outer") {
            u.add(c, 1)
            u.transaction("inner") { u.add(c, 2) }
        }
        assertEquals(1, u.undoDepth)
        u.undo()
        assertEquals(0, c.value)
    }

    @Test
    fun dirtyTrackingFollowsSavePoints() {
        val u = UndoManager()
        val c = Counter()
        assertFalse(u.isDirty)
        u.transaction("a") { u.add(c, 1) }
        assertTrue(u.isDirty)
        u.markSaved()
        assertFalse(u.isDirty)
        u.undo()
        assertTrue(u.isDirty)
        u.redo()
        assertFalse(u.isDirty)
        val marker = u.stateMarker
        u.transaction("b") { u.add(c, 1) }
        u.markSaved(marker)
        assertTrue(u.isDirty, "b happened after the snapshot")
    }

    @Test
    fun typingCoalescesIntoOneStep() {
        val u = UndoManager()
        val c = Counter()
        repeat(5) { u.transaction("Typing", "p1") { u.add(c, 1) } }
        u.transaction("Typing", "p2") { u.add(c, 1) }
        assertEquals(2, u.undoDepth)
        u.undo()
        u.undo()
        assertEquals(0, c.value)
    }

    @Test
    fun historyStack() {
        val h = HistoryStack(0)
        h.push(1); h.push(2); h.push(2)
        assertEquals(1, h.undo())
        assertEquals(0, h.undo())
        assertEquals(0, h.undo())
        assertEquals(1, h.redo())
        h.push(5)
        assertFalse(h.canRedo)
    }
}
