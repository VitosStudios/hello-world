package com.vitosstudios.suite.video

import com.vitosstudios.suite.common.HistoryStack

/**
 * Unlimited undo/redo for the video editor. Discrete operations ([apply]) are one step each;
 * continuous gestures such as dragging a trim handle or a volume slider ([drag] ... [endDrag])
 * update live and become one step when the gesture ends.
 */
class TimelineSession(initial: Timeline = Timeline()) {
    private val history = HistoryStack(initial)
    private var dragBase: Timeline? = null

    val timeline: Timeline get() = history.current
    val canUndo: Boolean get() = history.canUndo || dragBase != null
    val canRedo: Boolean get() = history.canRedo

    fun apply(op: (Timeline) -> Timeline): Timeline {
        endDrag()
        history.push(op(timeline))
        return timeline
    }

    fun drag(op: (Timeline) -> Timeline): Timeline {
        if (dragBase == null) dragBase = timeline
        history.replaceCurrent(op(timeline))
        return timeline
    }

    fun endDrag() {
        val base = dragBase ?: return
        dragBase = null
        val final = timeline
        history.replaceCurrent(base)
        history.push(final)
    }

    fun undo(): Timeline {
        endDrag()
        return history.undo()
    }

    fun redo(): Timeline {
        endDrag()
        return history.redo()
    }

    /** Starts over from a restored project (history is not persisted). */
    fun reset(t: Timeline) {
        dragBase = null
        history.reset(t)
    }
}
