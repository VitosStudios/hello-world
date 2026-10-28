package com.vitosstudios.suite.common

/**
 * One reversible change. [undo] restores the state before the change, [redo] re-applies it.
 * Changes are always undone in reverse order, so an implementation may rely on the state being
 * exactly what it left behind.
 */
interface Change {
    fun undo()
    fun redo()
}

/** Convenience [Change] built from two lambdas. */
class LambdaChange(private val onUndo: () -> Unit, private val onRedo: () -> Unit) : Change {
    override fun undo() = onUndo()
    override fun redo() = onRedo()
}

/**
 * Unlimited undo/redo for one editing session.
 *
 * Every model mutation reports a [Change] through [record]. Mutations are grouped into labelled
 * transactions (one user action = one transaction). A transaction that throws is rolled back, so
 * a failed edit never leaves a half-applied change in the model.
 */
class UndoManager {
    private class Transaction(val id: Long, val label: String) {
        val changes = ArrayList<Change>()
        fun undo() {
            for (i in changes.indices.reversed()) changes[i].undo()
        }
        fun redo() {
            for (c in changes) c.redo()
        }
    }

    private val undoStack = ArrayDeque<Transaction>()
    private val redoStack = ArrayDeque<Transaction>()
    private var open: Transaction? = null
    private var depth = 0
    private var nextId = 1L
    private var savedMarker: Long? = 0L
    private val listeners = LinkedHashSet<() -> Unit>()

    /** True while undo/redo replays changes; mutations are not recorded then. */
    var isReplaying: Boolean = false
        private set

    /** Increments on every edit, undo and redo. UIs observe it to refresh. */
    var version: Long = 0
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty() && depth == 0
    val canRedo: Boolean get() = redoStack.isNotEmpty() && depth == 0
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label
    val undoDepth: Int get() = undoStack.size

    /** True when the model differs from the state at the last [markSaved]. */
    val isDirty: Boolean get() = currentMarker() != savedMarker

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    /**
     * Runs [block] as one undoable step. Nested calls join the outermost transaction.
     * If [block] throws, every change it recorded is reverted before the exception propagates.
     */
    fun <T> transaction(label: String, block: () -> T): T {
        if (isReplaying) return block()
        depth++
        if (depth == 1) open = Transaction(nextId++, label)
        var ok = false
        try {
            val result = block()
            ok = true
            return result
        } finally {
            depth--
            if (depth == 0) {
                val t = open!!
                open = null
                if (!ok) {
                    isReplaying = true
                    try { t.undo() } finally { isReplaying = false }
                } else if (t.changes.isNotEmpty()) {
                    undoStack.addLast(t)
                    redoStack.clear()
                }
                if (t.changes.isNotEmpty()) bump()
            }
        }
    }

    /** Records [change]. Outside a transaction the change becomes its own step. */
    fun record(change: Change) {
        if (isReplaying) return
        val t = open
        if (t != null) {
            t.changes += change
        } else {
            val single = Transaction(nextId++, "Edit")
            single.changes += change
            undoStack.addLast(single)
            redoStack.clear()
            bump()
        }
    }

    fun undo(): Boolean {
        if (!canUndo) return false
        val t = undoStack.removeLast()
        replay { t.undo() }
        redoStack.addLast(t)
        bump()
        return true
    }

    fun redo(): Boolean {
        if (!canRedo) return false
        val t = redoStack.removeLast()
        replay { t.redo() }
        undoStack.addLast(t)
        bump()
        return true
    }

    /** Remembers the current state as the saved one (see [isDirty]). */
    fun markSaved() { savedMarker = currentMarker() }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        savedMarker = 0L
        bump()
    }

    private fun currentMarker(): Long = undoStack.lastOrNull()?.id ?: 0L

    private inline fun replay(block: () -> Unit) {
        isReplaying = true
        try { block() } finally { isReplaying = false }
    }

    private fun bump() {
        version++
        for (l in listeners.toList()) l()
    }
}

/**
 * Snapshot history for small immutable editor states (photo adjustments, video timelines).
 * Unlimited within a session.
 */
class HistoryStack<T>(initial: T) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()

    var current: T = initial
        private set

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Makes [state] current. Pushing a state equal to the current one is a no-op. */
    fun push(state: T) {
        if (state == current) return
        past.addLast(current)
        future.clear()
        current = state
    }

    /** Replaces the current state without creating a history entry (for live slider drags). */
    fun replaceCurrent(state: T) { current = state }

    fun undo(): T {
        if (past.isNotEmpty()) {
            future.addLast(current)
            current = past.removeLast()
        }
        return current
    }

    fun redo(): T {
        if (future.isNotEmpty()) {
            past.addLast(current)
            current = future.removeLast()
        }
        return current
    }

    fun reset(state: T) {
        past.clear(); future.clear(); current = state
    }
}
