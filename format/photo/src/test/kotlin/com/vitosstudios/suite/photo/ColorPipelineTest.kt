package com.vitosstudios.suite.photo

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorPipelineTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF
    private fun a(p: Int) = p ushr 24
    private fun luma(p: Int) = 0.2126 * r(p) + 0.7152 * g(p) + 0.0722 * b(p)

    /** A gradient plus random noise with varying alpha. */
    private fun testImage(w: Int = 64, h: Int = 48, seed: Int = 7): IntArray {
        val rnd = Random(seed)
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            argb(128 + rnd.nextInt(128), (x * 255 / (w - 1)), (y * 255 / (h - 1)), rnd.nextInt(256))
        }
    }

    private fun apply(px: IntArray, adj: Adjustments, w: Int = 64, h: Int = 48) = ColorPipeline.apply(px, w, h, adj)

    @Test
    fun identityReturnsExactCopy() {
        val px = testImage()
        val out = apply(px, Adjustments.NONE)
        assertContentEquals(px, out)
        assertTrue(px !== out, "Must be a copy")
    }

    @Test
    fun neutralSlidersInsideNonIdentityStateDoNotChangeOtherStages() {
        // Saturation 0 with warmth 0 and sharpness 0: only exposure moves pixels.
        val px = IntArray(256) { argb(255, it, it, it) }
        val out = ColorPipeline.apply(px, 16, 16, Adjustments(exposure = 0.5f))
        for (i in px.indices) {
            assertEquals(r(out[i]), g(out[i]))
            assertEquals(g(out[i]), b(out[i]), "Grey must stay grey")
        }
    }

    @Test
    fun alphaIsPreservedByEveryStage() {
        val px = testImage()
        for (kind in AdjustmentKind.values()) {
            val out = apply(px, Adjustments().with(kind, kind.max))
            for (i in px.indices) assertEquals(a(px[i]), a(out[i]), "$kind changed alpha")
        }
    }

    @Test
    fun exposureBrightensAndDarkens() {
        val px = testImage()
        val base = px.map(::luma).average()
        assertTrue(apply(px, Adjustments(exposure = 1f)).map(::luma).average() > base + 20)
        assertTrue(apply(px, Adjustments(exposure = -1f)).map(::luma).average() < base - 20)
        // One stop in linear light: mid grey 118 (18 % reflectance) becomes about 161.
        val grey = ColorPipeline.apply(intArrayOf(argb(255, 118, 118, 118)), 1, 1, Adjustments(exposure = 1f))[0]
        assertTrue(r(grey) in 155..168, "got ${r(grey)}")
        // Clamped at white, never wraps.
        val white = ColorPipeline.apply(intArrayOf(argb(255, 250, 250, 250)), 1, 1, Adjustments(exposure = 2f))[0]
        assertEquals(255, r(white))
    }

    @Test
    fun contrastSpreadsOrCompressesTones() {
        val dark = argb(255, 60, 60, 60)
        val light = argb(255, 200, 200, 200)
        val more = ColorPipeline.apply(intArrayOf(dark, light), 2, 1, Adjustments(contrast = 80f))
        assertTrue(r(more[0]) < 60 && r(more[1]) > 200)
        val less = ColorPipeline.apply(intArrayOf(dark, light), 2, 1, Adjustments(contrast = -80f))
        assertTrue(r(less[0]) > 60 && r(less[1]) < 200)
        // Mid grey is the pivot.
        val mid = ColorPipeline.apply(intArrayOf(argb(255, 128, 128, 128)), 1, 1, Adjustments(contrast = 100f))[0]
        assertTrue(r(mid) in 126..130)
    }

    @Test
    fun shadowsAndHighlightsActOnTheirRange() {
        val shadow = argb(255, 40, 40, 40)
        val highlight = argb(255, 220, 220, 220)
        val lifted = ColorPipeline.apply(intArrayOf(shadow, highlight), 2, 1, Adjustments(shadows = 100f))
        assertTrue(r(lifted[0]) > 55, "shadows +100 lifts dark tones, got ${r(lifted[0])}")
        assertEquals(220, r(lifted[1]), "shadows must not touch highlights")
        val recovered = ColorPipeline.apply(intArrayOf(shadow, highlight), 2, 1, Adjustments(highlights = -100f))
        assertTrue(r(recovered[1]) < 205, "highlights -100 darkens bright tones, got ${r(recovered[1])}")
        assertEquals(40, r(recovered[0]), "highlights must not touch shadows")
        // Pure black and white stay put.
        val ends = ColorPipeline.apply(intArrayOf(argb(255, 0, 0, 0), argb(255, 255, 255, 255)), 2, 1,
            Adjustments(shadows = 100f, highlights = 100f))
        assertEquals(0, r(ends[0]))
        assertEquals(255, r(ends[1]))
    }

    @Test
    fun toneCurvesAreMonotonic() {
        for (e in listOf(-2f, -1f, 0f, 1f, 2f)) for (c in listOf(-100f, -50f, 0f, 50f, 100f)) {
            val lut = ColorPipeline.toneCurve(e, c)
            for (i in 1..255) assertTrue(lut[i] >= lut[i - 1], "tone curve not monotonic at $i for e=$e c=$c")
        }
        for (h in listOf(-100f, 0f, 100f)) for (s in listOf(-100f, 0f, 100f)) {
            val gain = ColorPipeline.lumaGainCurve(h, s)
            var prev = -1.0
            for (l in 1..255) {
                val out = l * gain[l] / 4096.0
                assertTrue(out >= prev - 0.6, "luma curve not monotonic at $l for h=$h s=$s")
                prev = out
            }
        }
    }

    @Test
    fun saturationIsLumaPreserving() {
        val px = intArrayOf(argb(255, 200, 80, 40))
        val grey = ColorPipeline.apply(px, 1, 1, Adjustments(saturation = -100f))[0]
        assertEquals(r(grey), g(grey))
        assertEquals(g(grey), b(grey))
        assertTrue(kotlin.math.abs(luma(grey) - luma(px[0])) < 2.5)
        val vivid = ColorPipeline.apply(px, 1, 1, Adjustments(saturation = 100f))[0]
        assertTrue(r(vivid) - b(vivid) > 200 - 40)
    }

    @Test
    fun warmthShiftsRedAndBlueOppositely() {
        val px = intArrayOf(argb(255, 128, 128, 128))
        val warm = ColorPipeline.apply(px, 1, 1, Adjustments(warmth = 100f))[0]
        assertTrue(r(warm) > 128 && b(warm) < 128)
        val cool = ColorPipeline.apply(px, 1, 1, Adjustments(warmth = -100f))[0]
        assertTrue(r(cool) < 128 && b(cool) > 128)
    }

    @Test
    fun sharpnessIncreasesLocalContrastOnly() {
        val w = 9
        val h = 9
        // Vertical edge.
        val px = IntArray(w * h) { i -> if (i % w < 4) argb(255, 80, 80, 80) else argb(255, 170, 170, 170) }
        val out = ColorPipeline.apply(px, w, h, Adjustments(sharpness = 100f))
        val row = 4 * w
        assertTrue(r(out[row + 3]) < 80, "dark side of the edge gets darker")
        assertTrue(r(out[row + 4]) > 170, "bright side gets brighter")
        assertEquals(80, r(out[row + 0]), "flat areas are unchanged")
        assertEquals(170, r(out[row + 8]))
    }

    @Test
    fun valuesAreClampedToRanges() {
        val a = Adjustments(exposure = 9f, contrast = -500f, sharpness = -3f).clamped()
        assertEquals(2f, a.exposure)
        assertEquals(-100f, a.contrast)
        assertEquals(0f, a.sharpness)
        // Out-of-range input is clamped, not rejected.
        val px = testImage()
        assertContentEquals(apply(px, a), apply(px, Adjustments(exposure = 9f, contrast = -500f, sharpness = -3f)))
    }

    @Test
    fun parallelAndSequentialGiveTheSameResult() {
        val w = 300
        val h = 257
        val px = testImage(w, h)
        val adj = Adjustments(exposure = 0.3f, contrast = 20f, shadows = 30f, highlights = -20f, saturation = 15f, warmth = 10f, sharpness = 50f)
        assertContentEquals(ColorPipeline.apply(px, w, h, adj, parallel = false), ColorPipeline.apply(px, w, h, adj, parallel = true))
    }

    @Test
    fun twelveMegapixelPerformance() {
        val w = 4000
        val h = 3000
        val px = IntArray(w * h) { i -> argb(255, i and 0xFF, (i shr 8) and 0xFF, (i shr 16) and 0xFF) }
        val adj = Adjustments(exposure = 0.4f, contrast = 25f, highlights = -30f, shadows = 30f, saturation = 20f, warmth = 15f, sharpness = 40f)
        ColorPipeline.apply(px, w, h, adj) // warm-up
        val runs = 3
        val start = System.nanoTime()
        repeat(runs) { ColorPipeline.apply(px, w, h, adj) }
        val ms = (System.nanoTime() - start) / 1_000_000 / runs
        val colorOnly = adj.copy(sharpness = 0f)
        ColorPipeline.apply(px, w, h, colorOnly)
        val start2 = System.nanoTime()
        repeat(runs) { ColorPipeline.apply(px, w, h, colorOnly) }
        val ms2 = (System.nanoTime() - start2) / 1_000_000 / runs
        val single = System.nanoTime().let { t0 -> ColorPipeline.apply(px, w, h, adj, parallel = false); (System.nanoTime() - t0) / 1_000_000 }
        println("ColorPipeline 12 MP on ${Runtime.getRuntime().availableProcessors()} cores: all adjustments $ms ms, " +
            "without sharpness $ms2 ms, all adjustments single-threaded $single ms")
        assertTrue(ms < 5000, "12 MP took $ms ms")
    }
}
