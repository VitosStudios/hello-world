package com.vitosstudios.suite.photo

import java.util.stream.IntStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Tone and colour pipeline on packed ARGB_8888 pixels (the layout of `Bitmap.getPixels`).
 *
 * Order: exposure (linear light) and contrast as one per-channel curve, then highlights and
 * shadows as a luminance-masked gain, saturation (luma-preserving), warmth (per-channel curves),
 * and finally sharpness as an unsharp mask. Stages at their neutral value are skipped, so an
 * identity [Adjustments] returns an exact copy. Alpha is never changed. Every curve is monotonic,
 * so adjustments never invert tones.
 *
 * Integer arithmetic and lookup tables keep a 12 MP image well under a second on a desktop JVM;
 * rows are processed in parallel chunks. For live previews call [apply] on a downscaled buffer.
 */
object ColorPipeline {

    fun apply(pixels: IntArray, width: Int, height: Int, adjustments: Adjustments, parallel: Boolean = true): IntArray {
        require(width > 0 && height > 0 && pixels.size >= width * height) { "Buffer does not match $width x $height" }
        val adj = adjustments.clamped()
        if (adj.isIdentity) return pixels.copyOf(width * height)
        val plan = Plan(adj)
        val out = IntArray(width * height)
        if (plan.hasColorStage) {
            forRows(height, parallel) { y0, y1 -> colorRows(pixels, out, width, y0, y1, plan) }
        } else {
            System.arraycopy(pixels, 0, out, 0, width * height)
        }
        if (plan.sharpen == 0) return out
        val sharp = IntArray(width * height)
        forRows(height, parallel) { y0, y1 -> sharpenRows(out, sharp, width, height, y0, y1, plan.sharpen) }
        return sharp
    }

    /** Lookup tables and constants for one set of adjustments. */
    internal class Plan(adj: Adjustments) {
        /** Exposure + contrast curve, identical for R, G and B; null when both are neutral. */
        val tone: IntArray? = if (adj.exposure == 0f && adj.contrast == 0f) null else toneCurve(adj.exposure, adj.contrast)

        /** Highlights/shadows gain per luma value, 12-bit fixed point; null when neutral. */
        val lumaGain: IntArray? = if (adj.highlights == 0f && adj.shadows == 0f) null else lumaGainCurve(adj.highlights, adj.shadows)

        /** Saturation factor, 8-bit fixed point; 256 = neutral. */
        val saturation: Int = (256 * (1 + adj.saturation / 100f)).roundToInt()

        val warmR: IntArray?
        val warmG: IntArray?
        val warmB: IntArray?

        /** Unsharp-mask amount, 8-bit fixed point; 0 = off. */
        val sharpen: Int = (adj.sharpness / 100f * 1.2f * 256).roundToInt()

        init {
            if (adj.warmth == 0f) {
                warmR = null; warmG = null; warmB = null
            } else {
                val w = adj.warmth / 100.0
                warmR = shiftCurve(28.0 * w)
                warmG = shiftCurve(6.0 * w)
                warmB = shiftCurve(-28.0 * w)
            }
        }

        val hasColorStage: Boolean = tone != null || lumaGain != null || saturation != 256 || warmR != null
    }

    internal fun toneCurve(exposure: Float, contrast: Float): IntArray {
        val gain = 2.0.pow(exposure.toDouble())
        val c = contrast / 100.0
        return IntArray(256) { v ->
            var x = v / 255.0
            if (exposure != 0f) x = srgbEncode(min(1.0, srgbDecode(x) * gain))
            if (c > 0) {
                val s = x * x * (3 - 2 * x)
                x += c * (s - x)
            } else if (c < 0) {
                x = 0.5 + (x - 0.5) * (1 + 0.75 * c)
            }
            (x * 255).roundToInt().coerceIn(0, 255)
        }
    }

    /**
     * Luminance curve for shadows (acts below 60 % luma) and highlights (above 40 %), expressed as
     * a gain Lout/L. Both are `L * (1 + a * (1 - u)^2)` shapes, which stay monotonic for the
     * coefficient ranges used here; pure black and pure white never move.
     */
    internal fun lumaGainCurve(highlights: Float, shadows: Float): IntArray {
        val s = shadows / 100.0
        val h = highlights / 100.0
        val aShadow = s * (if (s > 0) 1.2 else 0.8)
        val aHigh = -h * (if (h > 0) 0.8 else 1.2)
        return IntArray(256) { v ->
            val l = max(v, 1) / 255.0
            var out = l
            val u = out / 0.6
            if (u < 1) out *= 1 + aShadow * (1 - u) * (1 - u)
            val d = 1 - out
            val w = d / 0.6
            if (w < 1) out = 1 - d * (1 + aHigh * (1 - w) * (1 - w))
            ((out / l) * 4096).roundToInt()
        }
    }

    /** Adds [amount] (in 8-bit levels) at mid-tones, fading to zero at black and white. */
    private fun shiftCurve(amount: Double): IntArray = IntArray(256) { v ->
        val x = v / 255.0
        (v + amount * 4 * x * (1 - x)).roundToInt().coerceIn(0, 255)
    }

    private fun srgbDecode(x: Double): Double = if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    private fun srgbEncode(x: Double): Double = if (x <= 0.0031308) x * 12.92 else 1.055 * x.pow(1 / 2.4) - 0.055

    private fun luma(r: Int, g: Int, b: Int): Int = (54 * r + 183 * g + 19 * b + 128) shr 8

    private fun colorRows(src: IntArray, dst: IntArray, width: Int, y0: Int, y1: Int, p: Plan) {
        val tone = p.tone
        val gainLut = p.lumaGain
        val sat = p.saturation
        val wr = p.warmR
        val wg = p.warmG
        val wb = p.warmB
        for (i in y0 * width until y1 * width) {
            val px = src[i]
            val a = px ushr 24
            var r = (px shr 16) and 0xFF
            var g = (px shr 8) and 0xFF
            var b = px and 0xFF
            if (tone != null) {
                r = tone[r]; g = tone[g]; b = tone[b]
            }
            if (gainLut != null) {
                val k = gainLut[luma(r, g, b)]
                r = clamp((r * k + 2048) shr 12)
                g = clamp((g * k + 2048) shr 12)
                b = clamp((b * k + 2048) shr 12)
            }
            if (sat != 256) {
                val l = luma(r, g, b)
                r = clamp(l + (((r - l) * sat) shr 8))
                g = clamp(l + (((g - l) * sat) shr 8))
                b = clamp(l + (((b - l) * sat) shr 8))
            }
            if (wr != null) {
                r = wr[r]; g = wg!![g]; b = wb!![b]
            }
            dst[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** 3x3 binomial blur ([1 2 1] squared) as the low-pass of the unsharp mask; edges clamp. */
    private fun sharpenRows(src: IntArray, dst: IntArray, width: Int, height: Int, y0: Int, y1: Int, amount: Int) {
        for (y in y0 until y1) {
            val up = max(y - 1, 0) * width
            val row = y * width
            val down = min(y + 1, height - 1) * width
            for (x in 0 until width) {
                val xl = max(x - 1, 0)
                val xr = min(x + 1, width - 1)
                val c = src[row + x]
                val n0 = src[up + xl]; val n1 = src[up + x]; val n2 = src[up + xr]
                val n3 = src[row + xl]; val n5 = src[row + xr]
                val n6 = src[down + xl]; val n7 = src[down + x]; val n8 = src[down + xr]
                // Red, green and blue unrolled: the weighted sum is 16 x the blurred value.
                val cr = (c shr 16) and 0xFF
                val sr = ((n0 shr 16) and 0xFF) + 2 * ((n1 shr 16) and 0xFF) + ((n2 shr 16) and 0xFF) +
                    2 * ((n3 shr 16) and 0xFF) + 4 * cr + 2 * ((n5 shr 16) and 0xFF) +
                    ((n6 shr 16) and 0xFF) + 2 * ((n7 shr 16) and 0xFF) + ((n8 shr 16) and 0xFF)
                val cg = (c shr 8) and 0xFF
                val sg = ((n0 shr 8) and 0xFF) + 2 * ((n1 shr 8) and 0xFF) + ((n2 shr 8) and 0xFF) +
                    2 * ((n3 shr 8) and 0xFF) + 4 * cg + 2 * ((n5 shr 8) and 0xFF) +
                    ((n6 shr 8) and 0xFF) + 2 * ((n7 shr 8) and 0xFF) + ((n8 shr 8) and 0xFF)
                val cb = c and 0xFF
                val sb = (n0 and 0xFF) + 2 * (n1 and 0xFF) + (n2 and 0xFF) + 2 * (n3 and 0xFF) + 4 * cb +
                    2 * (n5 and 0xFF) + (n6 and 0xFF) + 2 * (n7 and 0xFF) + (n8 and 0xFF)
                val r = clamp(cr + (((16 * cr - sr) * amount) shr 12))
                val g = clamp(cg + (((16 * cg - sg) * amount) shr 12))
                val b = clamp(cb + (((16 * cb - sb) * amount) shr 12))
                dst[row + x] = (c and -0x1000000) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    internal fun forRows(height: Int, parallel: Boolean, block: (Int, Int) -> Unit) {
        val cores = Runtime.getRuntime().availableProcessors()
        if (!parallel || cores <= 1 || height < 64) {
            block(0, height)
            return
        }
        val chunks = min(height, cores * 4)
        IntStream.range(0, chunks).parallel().forEach { k ->
            val y0 = (height.toLong() * k / chunks).toInt()
            val y1 = (height.toLong() * (k + 1) / chunks).toInt()
            if (y1 > y0) block(y0, y1)
        }
    }
}
