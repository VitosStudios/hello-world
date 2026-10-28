package com.vitosstudios.suite.photo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Integer pixel size. */
data class Size(val width: Int, val height: Int) {
    val longEdge: Int get() = max(width, height)
    val isPortrait: Boolean get() = height > width
    val pixels: Long get() = width.toLong() * height
}

/**
 * A 2D affine transform stored as a row-major 3x3 matrix
 * `[a, b, c, d, e, f, 0, 0, 1]` with `x' = a*x + b*y + c`, `y' = d*x + e*y + f`.
 * [values] has the layout expected by `android.graphics.Matrix.setValues`.
 */
class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    val values: FloatArray
        get() = floatArrayOf(a.toFloat(), b.toFloat(), c.toFloat(), d.toFloat(), e.toFloat(), f.toFloat(), 0f, 0f, 1f)

    /** Composition: first [o], then this. */
    fun after(o: Affine): Affine = Affine(
        a * o.a + b * o.d, a * o.b + b * o.e, a * o.c + b * o.f + c,
        d * o.a + e * o.d, d * o.b + e * o.e, d * o.c + e * o.f + f,
    )

    fun mapX(x: Double, y: Double): Double = a * x + b * y + c
    fun mapY(x: Double, y: Double): Double = d * x + e * y + f

    fun inverse(): Affine {
        val det = a * e - b * d
        require(abs(det) > 1e-12) { "Transform is not invertible" }
        val ia = e / det
        val ib = -b / det
        val id = -d / det
        val ie = a / det
        return Affine(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f))
    }

    override fun toString(): String = "Affine[$a, $b, $c; $d, $e, $f]"

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        fun translate(x: Double, y: Double) = Affine(1.0, 0.0, x, 0.0, 1.0, y)
        fun scale(sx: Double, sy: Double) = Affine(sx, 0.0, 0.0, 0.0, sy, 0.0)

        /** Clockwise on screen (y grows downwards). */
        fun rotateDegrees(deg: Double): Affine {
            val r = Math.toRadians(deg)
            val cs = cos(r)
            val sn = sin(r)
            return Affine(cs, -sn, 0.0, sn, cs, 0.0)
        }

        /** Exact clockwise quarter turns (no floating-point noise). */
        fun quarterTurns(turns: Int): Affine = when (turns.mod(4)) {
            0 -> IDENTITY
            1 -> Affine(0.0, -1.0, 0.0, 1.0, 0.0, 0.0)
            2 -> Affine(-1.0, 0.0, 0.0, 0.0, -1.0, 0.0)
            else -> Affine(0.0, 1.0, 0.0, -1.0, 0.0, 0.0)
        }
    }
}

/**
 * Pure geometry for the photo editor. Sizes are pixels of the upright source (after the EXIF
 * orientation was applied, see [ExifOrientation]).
 */
object PhotoGeometry {
    /** Size after flips and quarter turns. */
    fun rotatedSize(source: Size, g: Geometry): Size =
        if (g.quarterTurns.mod(2) == 0) source else Size(source.height, source.width)

    /**
     * Scale of the largest rectangle with the rotated frame's aspect ratio that fits inside the
     * frame rotated by [degrees]. 1 means nothing is lost.
     */
    fun straightenScale(rotated: Size, degrees: Float): Double {
        if (degrees == 0f) return 1.0
        val r = Math.toRadians(abs(degrees.toDouble()))
        val cs = cos(r)
        val sn = sin(r)
        val w = rotated.width.toDouble()
        val h = rotated.height.toDouble()
        return minOf(w / (w * cs + h * sn), h / (w * sn + h * cs))
    }

    /** Frame (before crop) in fractional pixels: rotated size times the straighten scale. */
    fun frameSize(source: Size, g: Geometry): Pair<Double, Double> {
        val rot = rotatedSize(source, g)
        val s = straightenScale(rot, g.straightenDegrees)
        return rot.width * s to rot.height * s
    }

    /** Frame size rounded to pixels: the size the crop overlay works on. */
    fun frameSizePx(source: Size, g: Geometry): Size {
        val (w, h) = frameSize(source, g)
        return Size(max(1, w.roundToInt()), max(1, h.roundToInt()))
    }

    /** Output size of the edit at full resolution. */
    fun outputSize(source: Size, g: Geometry): Size {
        val (fw, fh) = frameSize(source, g)
        val c = g.crop.normalized()
        return Size(max(1, (fw * c.width).roundToInt()), max(1, (fh * c.height).roundToInt()))
    }

    /**
     * Transform from upright-source pixel coordinates to output pixel coordinates, multiplied by
     * [scale] (exports below full size). The app draws the source bitmap with this matrix and
     * bilinear filtering onto a canvas of `scaledSize(outputSize(...), scale)`.
     */
    fun matrix(source: Size, g: Geometry, scale: Double = 1.0): Affine {
        val (fw, fh) = frameSize(source, g)
        val c = g.crop.normalized()
        var m = Affine.translate(-source.width / 2.0, -source.height / 2.0)
        if (g.flipH || g.flipV) m = Affine.scale(if (g.flipH) -1.0 else 1.0, if (g.flipV) -1.0 else 1.0).after(m)
        m = Affine.quarterTurns(g.quarterTurns).after(m)
        if (g.straightenDegrees != 0f) m = Affine.rotateDegrees(g.straightenDegrees.toDouble()).after(m)
        // Centre the (fractional) crop inside the rounded output so mirrored edits stay symmetric.
        val out = outputSize(source, g)
        val tx = fw / 2.0 - c.left * fw + (out.width - fw * c.width) / 2.0
        val ty = fh / 2.0 - c.top * fh + (out.height - fh * c.height) / 2.0
        m = Affine.translate(tx, ty).after(m)
        if (scale != 1.0) m = Affine.scale(scale, scale).after(m)
        return m
    }

    /** Uniform scale so the long edge is at most [maxLongEdge]; never upscales. */
    fun scaleForLongEdge(output: Size, maxLongEdge: Int?): Double =
        if (maxLongEdge == null || output.longEdge <= maxLongEdge) 1.0 else maxLongEdge.toDouble() / output.longEdge

    fun scaledSize(output: Size, scale: Double): Size =
        if (scale == 1.0) output
        else Size(max(1, (output.width * scale).roundToInt()), max(1, (output.height * scale).roundToInt()))

    /** Downscale factor for an on-screen preview whose long edge should be about [previewLongEdge]. */
    fun previewScale(source: Size, previewLongEdge: Int): Double = scaleForLongEdge(source, previewLongEdge)
}

/** Crop aspect presets for the crop tool. */
enum class AspectPreset(val label: String, private val w: Int, private val h: Int) {
    FREE("Free", 0, 0),
    ORIGINAL("Original", -1, -1),
    SQUARE("1:1", 1, 1),
    R4_3("4:3", 4, 3),
    R3_2("3:2", 3, 2),
    R16_9("16:9", 16, 9);

    /**
     * Width/height ratio in pixels for a frame of [frame] size, or null for [FREE]. Non-square
     * presets follow the frame's orientation (4:3 becomes 3:4 on a portrait frame). [ORIGINAL]
     * uses [originalSource]'s ratio, oriented like the frame.
     */
    fun ratioFor(frame: Size, originalSource: Size = frame): Double? = when (this) {
        FREE -> null
        ORIGINAL -> {
            val o = if (frame.isPortrait == originalSource.isPortrait) originalSource else Size(originalSource.height, originalSource.width)
            o.width.toDouble() / o.height
        }
        else -> if (frame.isPortrait && w != h) h.toDouble() / w else w.toDouble() / h
    }

    /** Largest centred crop with this preset's ratio, normalised to the frame. */
    fun centeredCrop(frame: Size, originalSource: Size = frame): CropRect {
        val ratio = ratioFor(frame, originalSource) ?: return CropRect.FULL
        return CropMath.fit(CropRect.FULL, ratio, frame)
    }
}

object CropMath {
    /**
     * Largest rectangle with pixel aspect [ratio] (width/height) inside [within], centred on it,
     * in normalised frame coordinates.
     */
    fun fit(within: CropRect, ratio: Double, frame: Size): CropRect {
        val pw = within.width.toDouble() * frame.width
        val ph = within.height.toDouble() * frame.height
        var w = pw
        var h = pw / ratio
        if (h > ph) { h = ph; w = ph * ratio }
        val cx = (within.left + within.right) / 2.0
        val cy = (within.top + within.bottom) / 2.0
        val nw = w / frame.width
        val nh = h / frame.height
        return CropRect((cx - nw / 2).toFloat(), (cy - nh / 2).toFloat(), (cx + nw / 2).toFloat(), (cy + nh / 2).toFloat())
    }

    /** Pixel aspect ratio (width/height) of [rect] on [frame]. */
    fun ratioOf(rect: CropRect, frame: Size): Double = (rect.width.toDouble() * frame.width) / (rect.height.toDouble() * frame.height)
}

/** How an EXIF orientation value turns the stored pixels upright: an optional horizontal flip, then clockwise turns. */
data class OrientationTransform(val quarterTurns: Int, val flipHFirst: Boolean)

/**
 * EXIF orientation (tag 0x0112, values 1..8). The app decodes the stored pixels, draws them once
 * with [matrix] to get the upright source, edits that, and writes orientation [NORMAL] on export.
 */
object ExifOrientation {
    const val NORMAL = 1

    fun transform(value: Int): OrientationTransform = when (value) {
        2 -> OrientationTransform(0, true) // mirror horizontal
        3 -> OrientationTransform(2, false) // rotate 180
        4 -> OrientationTransform(2, true) // mirror vertical
        5 -> OrientationTransform(3, true) // transpose
        6 -> OrientationTransform(1, false) // rotate 90 clockwise
        7 -> OrientationTransform(1, true) // transverse
        8 -> OrientationTransform(3, false) // rotate 270 clockwise
        else -> OrientationTransform(0, false)
    }

    /** Upright size of an image stored with [stored] size. */
    fun uprightSize(value: Int, stored: Size): Size =
        if (transform(value).quarterTurns.mod(2) == 0) stored else Size(stored.height, stored.width)

    /** Maps stored pixel coordinates to upright pixel coordinates. */
    fun matrix(value: Int, stored: Size): Affine {
        val t = transform(value)
        val upright = uprightSize(value, stored)
        var m = Affine.translate(-stored.width / 2.0, -stored.height / 2.0)
        if (t.flipHFirst) m = Affine.scale(-1.0, 1.0).after(m)
        m = Affine.quarterTurns(t.quarterTurns).after(m)
        return Affine.translate(upright.width / 2.0, upright.height / 2.0).after(m)
    }
}
