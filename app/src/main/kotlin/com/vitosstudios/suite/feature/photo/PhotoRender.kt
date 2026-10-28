package com.vitosstudios.suite.feature.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import com.vitosstudios.suite.photo.Adjustments
import com.vitosstudios.suite.photo.ColorPipeline
import com.vitosstudios.suite.photo.ExifOrientation
import com.vitosstudios.suite.photo.Geometry
import com.vitosstudios.suite.photo.PhotoEditState
import com.vitosstudios.suite.photo.PhotoGeometry
import com.vitosstudios.suite.photo.Size
import java.io.File

/** Bitmap side of the photo editor: decoding upright, geometry with a Matrix, colour via the core pipeline. */
object PhotoRender {
    /** EXIF orientation of a file (1 when absent or unreadable). */
    fun orientation(file: File): Int = try {
        ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Stored (not rotated) pixel size of an image file. */
    fun storedSize(file: File): Size {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return Size(o.outWidth, o.outHeight)
    }

    /** Decodes [file] upright (EXIF orientation applied) with its long edge at most about [maxEdge] (null = full size). */
    fun decodeUpright(file: File, maxEdge: Int?): Bitmap {
        val stored = storedSize(file)
        require(stored.width > 0 && stored.height > 0) { "This image cannot be decoded" }
        var sample = 1
        if (maxEdge != null) while (stored.longEdge / (sample * 2) >= maxEdge) sample *= 2
        val raw = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IllegalArgumentException("This image cannot be decoded")
        val orientation = orientation(file)
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) return raw
        val affine = ExifOrientation.matrix(orientation, Size(raw.width, raw.height))
        val upright = ExifOrientation.uprightSize(orientation, Size(raw.width, raw.height))
        val out = Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(raw, Matrix().apply { setValues(affine.values) }, Paint(Paint.FILTER_BITMAP_FLAG))
        raw.recycle()
        return out
    }

    /** Applies crop, rotation, flips and straighten; [scale] shrinks the output (export size presets). */
    fun applyGeometry(source: Bitmap, g: Geometry, scale: Double = 1.0): Bitmap {
        val src = Size(source.width, source.height)
        if (g.isIdentity && scale == 1.0) return source
        val out = PhotoGeometry.scaledSize(PhotoGeometry.outputSize(src, g), scale)
        val bmp = Bitmap.createBitmap(out.width.coerceAtLeast(1), out.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val m = Matrix().apply { setValues(PhotoGeometry.matrix(src, g, scale).values) }
        Canvas(bmp).drawBitmap(source, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return bmp
    }

    fun applyColor(bitmap: Bitmap, adjustments: Adjustments): Bitmap {
        if (adjustments.isIdentity) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val result = ColorPipeline.apply(pixels, w, h, adjustments)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(result, 0, w, 0, 0, w, h)
        return out
    }

    fun render(source: Bitmap, state: PhotoEditState, scale: Double = 1.0): Bitmap =
        applyColor(applyGeometry(source, state.geometry, scale), state.adjustments)
}

/** Plain-text recipe so edits survive process death; the photo itself is only re-encoded on Save/Export. */
object PhotoRecipe {
    fun encode(s: PhotoEditState): String = buildString {
        val g = s.geometry
        val a = s.adjustments
        appendLine("v=1")
        appendLine("crop=${g.crop.left},${g.crop.top},${g.crop.right},${g.crop.bottom}")
        appendLine("turns=${g.quarterTurns}")
        appendLine("flipH=${g.flipH}")
        appendLine("flipV=${g.flipV}")
        appendLine("straighten=${g.straightenDegrees}")
        appendLine("exposure=${a.exposure}")
        appendLine("contrast=${a.contrast}")
        appendLine("highlights=${a.highlights}")
        appendLine("shadows=${a.shadows}")
        appendLine("saturation=${a.saturation}")
        appendLine("warmth=${a.warmth}")
        appendLine("sharpness=${a.sharpness}")
        s.presetId?.let { appendLine("preset=$it") }
    }

    fun decode(text: String): PhotoEditState? = try {
        val m = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val c = m.getValue("crop").split(',').map { it.toFloat() }
        val geometry = Geometry(
            crop = com.vitosstudios.suite.photo.CropRect(c[0], c[1], c[2], c[3]),
            quarterTurns = m.getValue("turns").toInt(),
            flipH = m.getValue("flipH").toBoolean(),
            flipV = m.getValue("flipV").toBoolean(),
            straightenDegrees = m.getValue("straighten").toFloat(),
        )
        val adj = Adjustments(
            exposure = m.getValue("exposure").toFloat(), contrast = m.getValue("contrast").toFloat(),
            highlights = m.getValue("highlights").toFloat(), shadows = m.getValue("shadows").toFloat(),
            saturation = m.getValue("saturation").toFloat(), warmth = m.getValue("warmth").toFloat(),
            sharpness = m.getValue("sharpness").toFloat(),
        )
        PhotoEditState(geometry, adj, m["preset"])
    } catch (_: Exception) {
        null
    }
}
