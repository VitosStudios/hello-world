package com.vitosstudios.suite.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TimelineTest {
    /** Three clips: a (0-10 s), b (10-15 s), c (15-23 s). */
    private fun three() = Timeline().join(listOf("a.mp4" to 10_000L, "b.mp4" to 5_000L, "c.mp4" to 8_000L))

    private fun Timeline.uris() = clips.map { it.sourceUri }

    @Test
    fun addAndJoin() {
        val t = three()
        assertEquals(listOf("a.mp4", "b.mp4", "c.mp4"), t.uris())
        assertEquals(listOf("c1", "c2", "c3"), t.clips.map { it.id })
        assertEquals(23_000, t.durationMs)
        val inserted = t.addClip("x.mp4", 1_000, index = 1)
        assertEquals(listOf("a.mp4", "x.mp4", "b.mp4", "c.mp4"), inserted.uris())
        assertEquals("c4", inserted.clips[1].id)
        assertFailsWith<IllegalArgumentException> { t.addClip("bad.mp4", 0) }
    }

    @Test
    fun locateAndMapPositions() {
        val t = three()
        assertEquals(ClipLocation(0, 0, 0, 0), t.locate(0))
        assertEquals(ClipLocation(1, 10_000, 0, 0), t.locate(10_000), "boundary belongs to the next clip")
        assertEquals(ClipLocation(2, 15_000, 8_000, 8_000), t.locate(23_000), "end belongs to the last clip")
        assertEquals(ClipLocation(2, 15_000, 8_000, 8_000), t.locate(99_000), "clamped")
        val trimmed = t.trim(1, 1_000, 4_000)
        assertEquals(ClipLocation(1, 10_000, 500, 1_500), trimmed.locate(10_500))
        assertEquals(10_500, trimmed.timelinePosition(1, 1_500))
        assertEquals(10_000, trimmed.timelinePosition(1, 0), "source time before the in point clamps")
        assertNull(Timeline().locate(0))
    }

    @Test
    fun trimClampsToSourceAndMinimum() {
        val t = three()
        val a = t.trim(0, 2_000, 6_000)
        assertEquals(2_000, a.clips[0].inMs)
        assertEquals(6_000, a.clips[0].outMs)
        assertEquals(17_000, a.durationMs)
        val tooShort = t.trim(0, 5_000, 5_010)
        assertEquals(Timeline.MIN_CLIP_MS, tooShort.clips[0].durationMs)
        val inverted = t.trim(0, 9_990, 100)
        assertTrue(inverted.clips[0].durationMs >= Timeline.MIN_CLIP_MS)
        assertTrue(inverted.clips[0].outMs <= 10_000)
        val outside = t.trim(1, -500, 50_000)
        assertEquals(0, outside.clips[1].inMs)
        assertEquals(5_000, outside.clips[1].outMs)
        assertEquals(3_000, t.trimStart(2, 3_000).clips[2].inMs)
        assertEquals(4_000, t.trimEnd(2, 4_000).clips[2].outMs)
        // A source shorter than the minimum stays whole.
        val tiny = Timeline().addClip("tiny.mp4", 40).trim(0, 10, 20)
        assertEquals(0, tiny.clips[0].inMs)
        assertEquals(40, tiny.clips[0].outMs)
    }

    @Test
    fun splitInsideAndAtBoundaries() {
        val t = three()
        val s = t.splitAt(12_000)
        assertEquals(4, s.clips.size)
        assertEquals("c2", s.clips[1].id)
        assertEquals(0, s.clips[1].inMs); assertEquals(2_000, s.clips[1].outMs)
        assertEquals("c4", s.clips[2].id)
        assertEquals(2_000, s.clips[2].inMs); assertEquals(5_000, s.clips[2].outMs)
        assertEquals(t.durationMs, s.durationMs)
        assertSame(t, t.splitAt(10_000), "exactly on a boundary: nothing to split")
        assertSame(t, t.splitAt(10_050), "within the minimum of a boundary")
        assertSame(t, t.splitAt(0))
        assertSame(t, t.splitAt(23_000))
        val empty = Timeline()
        assertSame(empty, empty.splitAt(5))
        // Split keeps per-clip audio settings.
        val loud = t.setClipVolume(0, 1.5f).setClipMuted(0, true).splitAt(4_000)
        assertEquals(1.5f, loud.clips[1].volume)
        assertTrue(loud.clips[1].muted)
    }

    @Test
    fun deleteRangeAcrossClips() {
        val t = three()
        // 8 s .. 17 s: keeps a[0-8], drops b, keeps c[2-8].
        val d = t.deleteRange(8_000, 17_000)
        assertEquals(listOf("a.mp4", "c.mp4"), d.uris())
        assertEquals(8_000, d.clips[0].outMs)
        assertEquals(2_000, d.clips[1].inMs)
        assertEquals(14_000, d.durationMs)
        // Range inside one clip splits it; the right part gets a new id.
        val inside = t.deleteRange(2_000, 3_000)
        assertEquals(listOf("a.mp4", "a.mp4", "b.mp4", "c.mp4"), inside.uris())
        assertEquals(listOf("c1", "c4", "c2", "c3"), inside.clips.map { it.id })
        assertEquals(2_000, inside.clips[0].outMs); assertEquals(3_000, inside.clips[1].inMs)
        // Reversed and out-of-range arguments are normalised.
        assertEquals(d, t.deleteRange(17_000, 8_000))
        assertEquals(0, t.deleteRange(-5, 99_000).clips.size)
        assertSame(t, t.deleteRange(5_000, 5_000))
        // Remainders below the minimum disappear instead of leaving slivers.
        val sliver = t.deleteRange(50, 9_950)
        assertEquals(listOf("b.mp4", "c.mp4"), sliver.uris())
    }

    @Test
    fun deleteClipAndMoveOverlaysWithContent() {
        val t = three()
            .addOverlay("on b", 11_000, 14_000)
            .addOverlay("on c", 16_000, 20_000)
            .addOverlay("on a", 1_000, 2_000)
        val d = t.deleteClip(1)
        assertEquals(listOf("a.mp4", "c.mp4"), d.uris())
        assertEquals(listOf("on c", "on a"), d.overlays.map { it.text }, "overlay on the deleted clip is gone")
        val onC = d.overlays.first { it.text == "on c" }
        assertEquals(11_000, onC.startMs); assertEquals(15_000, onC.endMs)
        assertEquals(1_000, d.overlays.first { it.text == "on a" }.startMs)
        assertFailsWith<IllegalArgumentException> { t.deleteClip(3) }
        // An overlay spanning the cut shrinks.
        val spanning = three().addOverlay("span", 9_000, 16_000).deleteRange(10_000, 15_000).overlays.single()
        assertEquals(9_000, spanning.startMs); assertEquals(11_000, spanning.endMs)
    }

    @Test
    fun reorder() {
        val t = three()
        assertEquals(listOf("b.mp4", "c.mp4", "a.mp4"), t.moveClip(0, 2).uris())
        assertEquals(listOf("c.mp4", "a.mp4", "b.mp4"), t.moveClip(2, 0).uris())
        assertEquals(listOf("b.mp4", "c.mp4", "a.mp4"), t.moveClip(0, 99).uris(), "target is clamped")
        assertEquals(listOf("a.mp4", "c.mp4", "b.mp4"), t.moveClip(2, 1).uris())
        assertSame(t, t.moveClip(1, 1))
        assertFailsWith<IndexOutOfBoundsException> { t.moveClip(3, 0) }
        assertFailsWith<IndexOutOfBoundsException> { t.moveClip(-1, 0) }
        assertEquals(t.durationMs, t.moveClip(0, 2).durationMs)
    }

    @Test
    fun audio() {
        val t = three()
        assertEquals(2f, t.setClipVolume(0, 7f).clips[0].volume)
        assertEquals(0f, t.setClipVolume(0, -1f).clips[0].volume)
        assertEquals(0f, t.setClipMuted(1, true).clips[1].effectiveVolume)
        val m = t.setMusic("song.mp3", 60_000, volume = 0.4f, loop = false, offsetMs = 5_000)
        assertEquals(BackgroundMusic("song.mp3", 60_000, 0.4f, false, 5_000), m.music)
        assertEquals(1.2f, m.setMusicVolume(1.2f).music!!.volume)
        assertTrue(m.setMusicLoop(true).music!!.loop)
        assertEquals(59_999, m.setMusicOffset(80_000).music!!.offsetMs)
        assertNull(m.removeMusic().music)
        assertSame(t, t.setMusicVolume(0.1f), "no music: nothing to change")
    }

    @Test
    fun overlays() {
        val t = three()
        val o = t.addOverlay("Hello", -1_000, 2_000, fontSizeSp = 500f, x = 2f, y = -1f)
        val ov = o.overlays.single()
        assertEquals("t1", ov.id)
        assertEquals(0, ov.startMs)
        assertEquals(Timeline.MAX_FONT_SP, ov.fontSizeSp)
        assertEquals(1f, ov.x); assertEquals(0f, ov.y)
        assertEquals(listOf("Hello"), o.overlaysAt(1_999).map { it.text })
        assertTrue(o.overlaysAt(2_000).isEmpty(), "end is exclusive")
        val moved = o.updateOverlay("t1") { it.copy(text = "Bye", startMs = 20_000, endMs = 30_000, bold = true) }
        val m = moved.overlays.single()
        assertEquals("Bye", m.text); assertEquals(23_000, m.endMs); assertTrue(m.bold)
        assertTrue(o.updateOverlay("t1") { it.copy(startMs = 50_000, endMs = 60_000) }.overlays.isEmpty(), "outside: removed")
        assertSame(o, o.updateOverlay("nope") { it })
        assertTrue(o.removeOverlay("t1").overlays.isEmpty())
        val empty = Timeline()
        assertSame(empty, empty.addOverlay("x", 0, 1_000), "empty timeline has no room")
        // Trimming the timeline clamps overlays.
        val clamped = t.addOverlay("late", 17_000, 23_000).trim(2, 0, 4_000)
        assertEquals(19_000, clamped.overlays.single().endMs)
        assertEquals("t2", o.addOverlay("second", 0, 500).overlays[1].id)
    }

    @Test
    fun exportPlan() {
        val t = three().trim(1, 1_000, 3_000).setClipMuted(2, true)
            .setMusic("m.mp3", 5_000, volume = 0.5f, loop = false, offsetMs = 1_000)
            .addOverlay("Title", 0, 3_000, x = 0.5f, y = 0.1f, anchor = OverlayAnchor.TOP_CENTER)
            .withPreset(ExportPreset.VERTICAL_9_16)
        val plan = ExportPlanner.plan(t)
        assertEquals(1080, plan.width); assertEquals(1920, plan.height)
        assertEquals(20_000_000, plan.durationUs)
        assertEquals(listOf(0L, 10_000_000L, 12_000_000L), plan.clips.map { it.timelineStartUs })
        assertEquals(1_000_000, plan.clips[1].clippingStartUs); assertEquals(3_000_000, plan.clips[1].clippingEndUs)
        assertEquals(0f, plan.clips[2].volume)
        // Non-looping music ends with its file (it is shorter than the video).
        assertEquals(1_000_000, plan.music!!.clippingStartUs); assertEquals(5_000_000, plan.music!!.clippingEndUs)
        val looping = ExportPlanner.plan(t.setMusicLoop(true))
        assertEquals(5_000_000, looping.music!!.clippingEndUs)
        // Long non-looping music is cut at the end of the video.
        val longMusic = ExportPlanner.plan(t.setMusic("long.mp3", 600_000, loop = false))
        assertEquals(20_000_000, longMusic.music!!.clippingEndUs)
        val ov = plan.overlays.single()
        assertEquals(0f, ov.backgroundAnchorX); assertEquals(0.8f, ov.backgroundAnchorY, 1e-6f)
        assertEquals(0f, ov.overlayAnchorX); assertEquals(1f, ov.overlayAnchorY)
        assertTrue(ov.isVisibleAt(2_999_999)); assertTrue(!ov.isVisibleAt(3_000_000))
        assertFailsWith<IllegalArgumentException> { ExportPlanner.plan(Timeline()) }
        assertEquals(1280, ExportPlanner.plan(t, ExportPreset.P720).width)
        assertEquals("trip-720p.mp4", ExportPlanner.fileName("trip.mov", ExportPreset.P720))
    }

    @Test
    fun sessionUndoRedo() {
        val s = TimelineSession()
        s.apply { it.addClip("a.mp4", 10_000) }
        s.apply { it.addClip("b.mp4", 5_000) }
        s.apply { it.splitAt(3_000) }
        assertEquals(3, s.timeline.clips.size)
        // Dragging a trim handle is one step.
        for (v in 1..20) s.drag { it.trimEnd(0, 3_000 - v * 50L) }
        s.endDrag()
        assertEquals(2_000, s.timeline.clips[0].outMs)
        s.undo()
        assertEquals(3_000, s.timeline.clips[0].outMs)
        s.undo()
        assertEquals(2, s.timeline.clips.size)
        s.redo()
        assertEquals(3, s.timeline.clips.size)
        s.undo(); s.undo(); s.undo()
        assertEquals(0, s.timeline.clips.size)
        assertTrue(!s.canUndo)
    }
}
