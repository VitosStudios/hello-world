package com.vitosstudios.suite.photo

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Packed ARGB_8888 pixels with their size. */
class ArgbImage(val pixels: IntArray, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Buffer does not match $width x $height" }
    }

    val size: Size get() = Size(width, height)

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
}

/**
 * Pure raster operations. The app uses `Canvas.drawBitmap(bitmap, matrix, filterPaint)` with the
 * same matrices from [PhotoGeometry]; these implementations exist for tests, previews without a
 * canvas and as a reference for what the matrices mean.
 */
object Raster {
    /** Applies the geometry of [state] (and an optional export [scale]). */
    fun transform(src: ArgbImage, geometry: Geometry, scale: Double = 1.0): ArgbImage {
        val out = PhotoGeometry.scaledSize(PhotoGeometry.outputSize(src.size, geometry), scale)
        return applyMatrix(src, PhotoGeometry.matrix(src.size, geometry, scale), out)
    }

    /** Full edit: geometry then colour, as the export does. */
    fun render(src: ArgbImage, state: PhotoEditState, scale: Double = 1.0, parallel: Boolean = true): ArgbImage {
        val geo = if (state.geometry.isIdentity && scale == 1.0) src else transform(src, state.geometry, scale)
        val px = ColorPipeline.apply(geo.pixels, geo.width, geo.height, state.adjustments, parallel)
        return ArgbImage(px, geo.width, geo.height)
    }

    /** Turns stored pixels upright according to an EXIF orientation value. */
    fun orient(src: ArgbImage, exifOrientation: Int): ArgbImage {
        if (ExifOrientation.transform(exifOrientation) == OrientationTransform(0, false)) return src
        return applyMatrix(src, ExifOrientation.matrix(exifOrientation, src.size), ExifOrientation.uprightSize(exifOrientation, src.size))
    }

    /**
     * Inverse-maps every output pixel centre through [m] and samples [src] bilinearly, clamping at
     * the edges. Pixel-aligned transforms (quarter turns, flips, integer crops) copy pixels exactly.
     */
    fun applyMatrix(src: ArgbImage, m: Affine, out: Size): ArgbImage {
        val inv = m.inverse()
        val w = src.width
        val h = src.height
        val sp = src.pixels
        val dst = IntArray(out.width * out.height)
        ColorPipeline.forRows(out.height, parallel = true) { y0, y1 ->
            for (oy in y0 until y1) {
                val cy = oy + 0.5
                for (ox in 0 until out.width) {
                    val cx = ox + 0.5
                    val sx = inv.mapX(cx, cy) - 0.5
                    val sy = inv.mapY(cx, cy) - 0.5
                    dst[oy * out.width + ox] = sample(sp, w, h, sx, sy)
                }
            }
        }
        return ArgbImage(dst, out.width, out.height)
    }

    private fun sample(sp: IntArray, w: Int, h: Int, sx: Double, sy: Double): Int {
        // Snap values that are within floating-point noise of a pixel centre.
        val rx = sx.roundToInt()
        val ry = sy.roundToInt()
        val fxRaw = sx - rx
        val fyRaw = sy - ry
        if (kotlin.math.abs(fxRaw) < 1e-6 && kotlin.math.abs(fyRaw) < 1e-6) {
            return sp[ry.coerceIn(0, h - 1) * w + rx.coerceIn(0, w - 1)]
        }
        val x0f = floor(sx)
        val y0f = floor(sy)
        val fx = sx - x0f
        val fy = sy - y0f
        val x0 = x0f.toInt().coerceIn(0, w - 1)
        val y0 = y0f.toInt().coerceIn(0, h - 1)
        val x1 = (x0f.toInt() + 1).coerceIn(0, w - 1)
        val y1 = (y0f.toInt() + 1).coerceIn(0, h - 1)
        val p00 = sp[y0 * w + x0]
        val p10 = sp[y0 * w + x1]
        val p01 = sp[y1 * w + x0]
        val p11 = sp[y1 * w + x1]
        var result = 0
        var shift = 24
        while (shift >= 0) {
            val c00 = (p00 ushr shift) and 0xFF
            val c10 = (p10 ushr shift) and 0xFF
            val c01 = (p01 ushr shift) and 0xFF
            val c11 = (p11 ushr shift) and 0xFF
            val top = c00 + (c10 - c00) * fx
            val bottom = c01 + (c11 - c01) * fx
            val v = (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
            result = result or (v shl shift)
            shift -= 8
        }
        return result
    }

    /** Box-filter downscale so the long edge is at most [maxLongEdge] (for fast previews). */
    fun downscale(src: ArgbImage, maxLongEdge: Int): ArgbImage {
        val scale = PhotoGeometry.scaleForLongEdge(src.size, maxLongEdge)
        if (scale >= 1.0) return src
        val out = PhotoGeometry.scaledSize(src.size, scale)
        val dst = IntArray(out.width * out.height)
        val fx = src.width.toDouble() / out.width
        val fy = src.height.toDouble() / out.height
        for (oy in 0 until out.height) {
            val sy0 = (oy * fy).toInt()
            val sy1 = max(sy0 + 1, ((oy + 1) * fy).toInt().coerceAtMost(src.height))
            for (ox in 0 until out.width) {
                val sx0 = (ox * fx).toInt()
                val sx1 = max(sx0 + 1, ((ox + 1) * fx).toInt().coerceAtMost(src.width))
                var a = 0L; var r = 0L; var g = 0L; var b = 0L
                for (y in sy0 until sy1) for (x in sx0 until sx1) {
                    val p = src.pixels[y * src.width + x]
                    a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
                }
                val n = ((sy1 - sy0) * (sx1 - sx0)).toLong()
                dst[oy * out.width + ox] = (((a + n / 2) / n).toInt() shl 24) or (((r + n / 2) / n).toInt() shl 16) or
                    (((g + n / 2) / n).toInt() shl 8) or ((b + n / 2) / n).toInt()
            }
        }
        return ArgbImage(dst, out.width, out.height)
    }
}
