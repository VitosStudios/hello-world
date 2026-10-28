package com.vitosstudios.suite.video

/**
 * One segment of a source video on the single video track. [inMs]..[outMs] is the used part of
 * the source; the clip lasts [durationMs] on the timeline.
 */
data class Clip(
    val id: String,
    val sourceUri: String,
    val sourceDurationMs: Long,
    val inMs: Long,
    val outMs: Long,
    /** 0..2; 1 is the original level. */
    val volume: Float = 1f,
    val muted: Boolean = false,
) {
    val durationMs: Long get() = outMs - inMs

    /** Volume actually applied (0 when muted). */
    val effectiveVolume: Float get() = if (muted) 0f else volume
}

/** Music mixed under the clips' own audio (the single extra audio track). */
data class BackgroundMusic(
    val uri: String,
    val durationMs: Long,
    /** 0..2. */
    val volume: Float = 0.6f,
    /** Repeat until the video ends. */
    val loop: Boolean = true,
    /** Where playback starts inside the music file (skips an intro). */
    val offsetMs: Long = 0,
)

/** Which point of the text box is placed at the overlay position. */
enum class OverlayAnchor(val fx: Float, val fy: Float) {
    TOP_LEFT(0f, 0f), TOP_CENTER(0.5f, 0f), TOP_RIGHT(1f, 0f),
    CENTER_LEFT(0f, 0.5f), CENTER(0.5f, 0.5f), CENTER_RIGHT(1f, 0.5f),
    BOTTOM_LEFT(0f, 1f), BOTTOM_CENTER(0.5f, 1f), BOTTOM_RIGHT(1f, 1f),
}

/**
 * Text shown over the video from [startMs] to [endMs] (timeline time). [x] and [y] are
 * normalised to the output frame (0,0 = top left).
 */
data class TextOverlay(
    val id: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val fontSizeSp: Float = 32f,
    val colorArgb: Int = -0x1, // opaque white
    val x: Float = 0.5f,
    val y: Float = 0.9f,
    val anchor: OverlayAnchor = OverlayAnchor.BOTTOM_CENTER,
    val bold: Boolean = false,
) {
    val durationMs: Long get() = endMs - startMs
    fun isActiveAt(positionMs: Long): Boolean = positionMs in startMs until endMs
}

/** Output presets offered by "Export". */
enum class ExportPreset(val label: String, val width: Int, val height: Int, val videoBitrate: Int, val audioBitrate: Int = 128_000) {
    P1080("1080p", 1920, 1080, 10_000_000),
    P720("720p", 1280, 720, 5_000_000),
    VERTICAL_9_16("Vertical 9:16", 1080, 1920, 10_000_000),
}

/** Position of a timeline instant inside a clip. */
data class ClipLocation(val index: Int, val clipStartMs: Long, val offsetInClipMs: Long, val sourceTimeMs: Long)

/**
 * Immutable editing model: one video track ([clips] in order), one optional music track, text
 * overlays and the export preset. Every operation returns a new timeline, so a
 * [com.vitosstudios.suite.common.HistoryStack] gives unlimited undo/redo for free.
 */
data class Timeline(
    val clips: List<Clip> = emptyList(),
    val music: BackgroundMusic? = null,
    val overlays: List<TextOverlay> = emptyList(),
    val preset: ExportPreset = ExportPreset.P1080,
) {
    val durationMs: Long get() = clips.sumOf { it.durationMs }

    fun clipStartMs(index: Int): Long {
        require(index in 0..clips.size) { "No clip $index" }
        var t = 0L
        for (i in 0 until index) t += clips[i].durationMs
        return t
    }

    fun clipIndex(id: String): Int = clips.indexOfFirst { it.id == id }

    /**
     * The clip under [positionMs]. A position on a boundary belongs to the clip that starts there;
     * the very end of the timeline belongs to the last clip. Null for an empty timeline.
     */
    fun locate(positionMs: Long): ClipLocation? {
        if (clips.isEmpty()) return null
        val p = positionMs.coerceIn(0, durationMs)
        var start = 0L
        for ((i, c) in clips.withIndex()) {
            val end = start + c.durationMs
            if (p < end || i == clips.lastIndex) {
                val offset = (p - start).coerceIn(0, c.durationMs)
                return ClipLocation(i, start, offset, c.inMs + offset)
            }
            start = end
        }
        return null
    }

    /** Timeline position of [sourceTimeMs] in clip [index] (clamped to the clip's used range). */
    fun timelinePosition(index: Int, sourceTimeMs: Long): Long {
        val c = clips[index]
        return clipStartMs(index) + (sourceTimeMs.coerceIn(c.inMs, c.outMs) - c.inMs)
    }

    fun overlaysAt(positionMs: Long): List<TextOverlay> = overlays.filter { it.isActiveAt(positionMs) }

    // ------------------------------------------------------------------------------------------
    // Clips

    /** Appends (or inserts at [index]) a clip using the whole source. */
    fun addClip(sourceUri: String, sourceDurationMs: Long, index: Int = clips.size): Timeline {
        require(sourceDurationMs > 0) { "Source has no duration" }
        val clip = Clip(nextId("c", clips.map { it.id }), sourceUri, sourceDurationMs, 0, sourceDurationMs)
        val list = clips.toMutableList().apply { add(index.coerceIn(0, size), clip) }
        return copy(clips = list).clampOverlays()
    }

    /** Joins several sources at the end, in order. */
    fun join(sources: List<Pair<String, Long>>): Timeline = sources.fold(this) { t, (uri, d) -> t.addClip(uri, d) }

    /**
     * Sets the used source range of clip [index]. Values are clamped to the source and to a
     * minimum length of [MIN_CLIP_MS] (or the whole source if it is shorter).
     */
    fun trim(index: Int, inMs: Long, outMs: Long): Timeline {
        val c = clips[index]
        val min = minOf(MIN_CLIP_MS, c.sourceDurationMs)
        var i = inMs.coerceIn(0, c.sourceDurationMs - min)
        var o = outMs.coerceIn(i + min, c.sourceDurationMs)
        if (o - i < min) { i = (o - min).coerceAtLeast(0); o = i + min }
        return replaceClip(index, c.copy(inMs = i, outMs = o)).clampOverlays()
    }

    fun trimStart(index: Int, inMs: Long): Timeline = trim(index, inMs, clips[index].outMs)
    fun trimEnd(index: Int, outMs: Long): Timeline = trim(index, clips[index].inMs, outMs)

    /**
     * Splits the clip under [positionMs] into two. Positions closer than [MIN_CLIP_MS] to a clip
     * boundary change nothing (there is nothing to split there).
     */
    fun splitAt(positionMs: Long): Timeline {
        val loc = locate(positionMs) ?: return this
        val c = clips[loc.index]
        if (loc.offsetInClipMs < MIN_CLIP_MS || c.durationMs - loc.offsetInClipMs < MIN_CLIP_MS) return this
        val cut = c.inMs + loc.offsetInClipMs
        val first = c.copy(outMs = cut)
        val second = c.copy(id = nextId("c", clips.map { it.id }), inMs = cut)
        val list = clips.toMutableList().apply {
            set(loc.index, first)
            add(loc.index + 1, second)
        }
        return copy(clips = list)
    }

    /**
     * Removes the timeline range [startMs, endMs) across clip boundaries. Clips inside the range
     * disappear, clips crossing it are trimmed, a range inside one clip splits it. Remainders
     * shorter than [MIN_CLIP_MS] are dropped. Overlays move with the content after the range.
     */
    fun deleteRange(startMs: Long, endMs: Long): Timeline {
        val s = minOf(startMs, endMs).coerceIn(0, durationMs)
        val e = maxOf(startMs, endMs).coerceIn(0, durationMs)
        if (e <= s) return this
        val ids = clips.map { it.id }.toMutableList()
        val kept = ArrayList<Clip>()
        val segments = ArrayList<Segment>() // old timeline intervals that survive, in order
        var start = 0L
        for (c in clips) {
            val end = start + c.durationMs
            // Left part [start, min(end, s)) and right part [max(start, e), end).
            if (s > start) {
                val leftEnd = minOf(end, s)
                if (leftEnd - start >= MIN_CLIP_MS) {
                    kept += c.copy(outMs = c.inMs + (leftEnd - start))
                    segments += Segment(start, leftEnd)
                }
            }
            if (e < end) {
                val rightStart = maxOf(start, e)
                if (end - rightStart >= MIN_CLIP_MS) {
                    val leftKept = s > start && minOf(end, s) - start >= MIN_CLIP_MS
                    val id = if (leftKept) nextId("c", ids).also { ids += it } else c.id
                    kept += c.copy(id = id, inMs = c.inMs + (rightStart - start))
                    segments += Segment(rightStart, end)
                }
            }
            start = end
        }
        return copy(clips = kept, overlays = remapOverlays(segments)).clampOverlays()
    }

    fun deleteClip(index: Int): Timeline {
        require(index in clips.indices) { "No clip $index" }
        val segments = ArrayList<Segment>()
        var t = 0L
        for ((i, c) in clips.withIndex()) {
            if (i != index) segments += Segment(t, t + c.durationMs)
            t += c.durationMs
        }
        return copy(clips = clips.filterIndexed { i, _ -> i != index }, overlays = remapOverlays(segments)).clampOverlays()
    }

    /** Moves clip [from] to position [to] (clamped). Overlays keep their timeline times. */
    fun moveClip(from: Int, to: Int): Timeline {
        if (from !in clips.indices) throw IndexOutOfBoundsException("No clip $from")
        val target = to.coerceIn(0, clips.lastIndex)
        if (target == from) return this
        val list = clips.toMutableList()
        val c = list.removeAt(from)
        list.add(target, c)
        return copy(clips = list)
    }

    fun setClipVolume(index: Int, volume: Float): Timeline =
        replaceClip(index, clips[index].copy(volume = volume.coerceIn(0f, MAX_VOLUME)))

    fun setClipMuted(index: Int, muted: Boolean): Timeline = replaceClip(index, clips[index].copy(muted = muted))

    private fun replaceClip(index: Int, clip: Clip): Timeline = copy(clips = clips.toMutableList().apply { set(index, clip) })

    // ------------------------------------------------------------------------------------------
    // Music

    fun setMusic(uri: String, durationMs: Long, volume: Float = 0.6f, loop: Boolean = true, offsetMs: Long = 0): Timeline {
        require(durationMs > 0) { "Music has no duration" }
        return copy(music = BackgroundMusic(uri, durationMs, volume.coerceIn(0f, MAX_VOLUME), loop, offsetMs.coerceIn(0, durationMs - 1)))
    }

    fun removeMusic(): Timeline = copy(music = null)

    fun setMusicVolume(volume: Float): Timeline = music?.let { copy(music = it.copy(volume = volume.coerceIn(0f, MAX_VOLUME))) } ?: this

    fun setMusicLoop(loop: Boolean): Timeline = music?.let { copy(music = it.copy(loop = loop)) } ?: this

    fun setMusicOffset(offsetMs: Long): Timeline = music?.let { copy(music = it.copy(offsetMs = offsetMs.coerceIn(0, it.durationMs - 1))) } ?: this

    // ------------------------------------------------------------------------------------------
    // Overlays

    /**
     * Adds a text overlay clamped to the timeline. Returns the timeline unchanged when there is
     * no room for an overlay of at least [MIN_OVERLAY_MS] (for example an empty timeline).
     */
    fun addOverlay(
        text: String,
        startMs: Long,
        endMs: Long,
        fontSizeSp: Float = 32f,
        colorArgb: Int = -0x1,
        x: Float = 0.5f,
        y: Float = 0.9f,
        anchor: OverlayAnchor = OverlayAnchor.BOTTOM_CENTER,
        bold: Boolean = false,
    ): Timeline {
        val o = TextOverlay(nextId("t", overlays.map { it.id }), text, startMs, endMs, fontSizeSp, colorArgb, x, y, anchor, bold)
        val clamped = clampOverlay(o) ?: return this
        return copy(overlays = overlays + clamped)
    }

    /** Applies [change] to overlay [id]; an overlay that ends up too short is removed. */
    fun updateOverlay(id: String, change: (TextOverlay) -> TextOverlay): Timeline {
        val idx = overlays.indexOfFirst { it.id == id }
        if (idx < 0) return this
        val updated = clampOverlay(change(overlays[idx]).copy(id = id))
        val list = overlays.toMutableList()
        if (updated == null) list.removeAt(idx) else list[idx] = updated
        return copy(overlays = list)
    }

    fun removeOverlay(id: String): Timeline = copy(overlays = overlays.filterNot { it.id == id })

    /** Clamps every overlay into [0, durationMs]; overlays left shorter than [MIN_OVERLAY_MS] are removed. */
    fun clampOverlays(): Timeline {
        val list = overlays.mapNotNull(::clampOverlay)
        return if (list == overlays) this else copy(overlays = list)
    }

    private fun clampOverlay(o: TextOverlay): TextOverlay? {
        val d = durationMs
        val s = minOf(o.startMs, o.endMs).coerceIn(0, d)
        val e = maxOf(o.startMs, o.endMs).coerceIn(0, d)
        if (e - s < MIN_OVERLAY_MS) return null
        return o.copy(
            startMs = s, endMs = e,
            x = o.x.coerceIn(0f, 1f), y = o.y.coerceIn(0f, 1f),
            fontSizeSp = o.fontSizeSp.coerceIn(MIN_FONT_SP, MAX_FONT_SP),
        )
    }

    /** Maps overlay times through the surviving old-timeline [segments] (deleted spans collapse). */
    private fun remapOverlays(segments: List<Segment>): List<TextOverlay> {
        fun map(t: Long): Long {
            var newStart = 0L
            for (seg in segments) {
                if (t < seg.start) return newStart
                if (t <= seg.end) return newStart + (t - seg.start)
                newStart += seg.end - seg.start
            }
            return newStart
        }
        return overlays.map { it.copy(startMs = map(it.startMs), endMs = map(it.endMs)) }
    }

    fun withPreset(p: ExportPreset): Timeline = copy(preset = p)

    private data class Segment(val start: Long, val end: Long)

    companion object {
        /** Shortest clip the editor creates (trim, split and delete never go below it). */
        const val MIN_CLIP_MS = 100L
        const val MIN_OVERLAY_MS = 100L
        const val MAX_VOLUME = 2f
        const val MIN_FONT_SP = 8f
        const val MAX_FONT_SP = 200f

        /** Next free id "prefixN" (deterministic, so operations stay pure). */
        internal fun nextId(prefix: String, existing: Collection<String>): String {
            val max = existing.mapNotNull { if (it.startsWith(prefix)) it.substring(prefix.length).toIntOrNull() else null }.maxOrNull() ?: 0
            return "$prefix${max + 1}"
        }
    }
}
