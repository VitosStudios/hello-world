package com.vitosstudios.suite.video

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineCodecTest {
    private fun sample(): Timeline = Timeline()
        .join(listOf("content://media/external/video/1" to 12_345L, "file:///sdcard/Clip \"two\".mp4" to 4_000L))
        .trim(0, 1_234, 9_876)
        .setClipVolume(1, 1.75f)
        .setClipMuted(0, true)
        .setMusic("content://audio/9?x=1&y=2", 180_000, volume = 0.33f, loop = false, offsetMs = 7_500)
        .addOverlay("Line one\nLine \"two\" \\ tab\t é ✓ 😀", 500, 4_000, fontSizeSp = 41.5f, colorArgb = 0x80FF0000.toInt(),
            x = 0.25f, y = 0.125f, anchor = OverlayAnchor.TOP_LEFT, bold = true)
        .addOverlay("Second", 1_000, 2_000)
        .withPreset(ExportPreset.P720)

    @Test
    fun roundTrip() {
        val t = sample()
        val text = TimelineCodec.encode(t)
        assertEquals(t, TimelineCodec.decode(text))
        assertEquals(Timeline(), TimelineCodec.decode(TimelineCodec.encode(Timeline())))
        assertTrue(text.startsWith("{\"format\":\"suite-video-timeline\",\"version\":1,"))
    }

    @Test
    fun toleratesUnknownKeysAndWhitespace() {
        val text = TimelineCodec.encode(sample())
            .replace("{\"format\"", "{ \"future\" : [1, 2.5e3, {\"x\": null}] ,\n \"format\"")
        assertEquals(sample(), TimelineCodec.decode(text))
    }

    @Test
    fun rejectsGarbageAndNewerVersions() {
        assertFailsWith<TimelineFormatException> { TimelineCodec.decode("") }
        assertFailsWith<TimelineFormatException> { TimelineCodec.decode("{\"format\":\"other\",\"version\":1}") }
        assertFailsWith<TimelineFormatException> { TimelineCodec.decode("{\"format\":\"suite-video-timeline\",\"version\":99}") }
        assertFailsWith<TimelineFormatException> { TimelineCodec.decode("{\"format\":\"suite-video-timeline\",\"version\":1,\"clips\":[{\"id\":\"c1\"}]}") }
        assertFailsWith<TimelineFormatException> { TimelineCodec.decode(TimelineCodec.encode(sample()).dropLast(3)) }
    }

    @Test
    fun atomicStoreSaveAndLoad() {
        val dir = Files.createTempDirectory("timeline").toFile()
        try {
            val f = File(dir, "project.timeline.json")
            assertNull(TimelineStore.load(f))
            TimelineStore.save(f, sample())
            assertEquals(sample(), TimelineStore.load(f))
            val shorter = sample().deleteClip(1)
            TimelineStore.save(f, shorter)
            assertEquals(shorter, TimelineStore.load(f))
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".partial") }, "no temporary files left")
            f.writeText("{broken")
            assertFailsWith<TimelineFormatException> { TimelineStore.load(f) }
        } finally {
            dir.deleteRecursively()
        }
    }
}
