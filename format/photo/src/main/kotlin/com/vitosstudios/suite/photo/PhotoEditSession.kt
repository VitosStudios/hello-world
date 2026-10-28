package com.vitosstudios.suite.photo

import com.vitosstudios.suite.common.HistoryStack

/**
 * Unlimited undo/redo for one photo. Discrete actions ([apply]) become one history step each;
 * slider drags ([drag] ... [endDrag]) update the state live and become a single step when the
 * finger lifts.
 */
class PhotoEditSession(initial: PhotoEditState = PhotoEditState()) {
    private val history = HistoryStack(initial)
    private var dragBase: PhotoEditState? = null

    val state: PhotoEditState get() = history.current
    val canUndo: Boolean get() = history.canUndo || dragBase != null
    val canRedo: Boolean get() = history.canRedo

    fun apply(change: (PhotoEditState) -> PhotoEditState): PhotoEditState {
        endDrag()
        history.push(change(state))
        return state
    }

    /** Live update during a gesture; no history entry until [endDrag]. */
    fun drag(change: (PhotoEditState) -> PhotoEditState): PhotoEditState {
        if (dragBase == null) dragBase = state
        history.replaceCurrent(change(state))
        return state
    }

    fun endDrag() {
        val base = dragBase ?: return
        dragBase = null
        val final = state
        history.replaceCurrent(base)
        history.push(final)
    }

    fun undo(): PhotoEditState {
        endDrag()
        return history.undo()
    }

    fun redo(): PhotoEditState {
        endDrag()
        return history.redo()
    }

    /** Replaces the whole history, e.g. after restoring a saved edit. */
    fun reset(state: PhotoEditState) {
        dragBase = null
        history.reset(state)
    }
}
