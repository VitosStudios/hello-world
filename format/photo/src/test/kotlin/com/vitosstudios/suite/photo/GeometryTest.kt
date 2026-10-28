package com.vitosstudios.suite.photo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeometryTest {
    /** Every pixel unique: value encodes its coordinates. */
    private fun coded(w: Int, h: Int) = ArgbImage(IntArray(w * h) { i -> (0xFF shl 24) or ((i % w) shl 12) or (i / w) }, w, h)

    private fun image(w: Int, h: Int, f: (Int, Int) -> Int) = ArgbImage(IntArray(w * h) { i -> f(i % w, i / w) }, w, h)

    @Test
    fun sizesForTurnsAndCrops() {
        val src = Size(4000, 3000)
        assertEquals(Size(4000, 3000), PhotoGeometry.outputSize(src, Geometry()))
        assertEquals(Size(3000, 4000), PhotoGeometry.outputSize(src, Geometry().rotateClockwise()))
        assertEquals(Size(4000, 3000), PhotoGeometry.outputSize(src, Geometry().rotateClockwise().rotateClockwise()))
        assertEquals(Size(2000, 1500), PhotoGeometry.outputSize(src, Geometry(crop = CropRect(0.25f, 0.25f, 0.75f, 0.75f))))
        // Crop is in the turned frame: left half of a portrait frame.
        assertEquals(Size(1500, 4000), PhotoGeometry.outputSize(src, Geometry(quarterTurns = 1, crop = CropRect(0f, 0f, 0.5f, 1f))))
    }

    @Test
    fun straightenCropsInsideTheImage() {
        val src = Size(4000, 3000)
        for (deg in listOf(-45f, -10f, -1f, 1f, 5f, 30f, 45f)) {
            val g = Geometry().withStraighten(deg)
            val out = PhotoGeometry.outputSize(src, g)
            assertTrue(out.width < 4000 && out.height < 3000, "straighten $deg must crop")
            assertEquals(4.0 / 3.0, out.width.toDouble() / out.height, 0.01, "aspect is kept")
            // Every output corner maps back inside the source: no empty corners.
            val inv = PhotoGeometry.matrix(src, g).inverse()
            for ((x, y) in listOf(0.0 to 0.0, out.width.toDouble() to 0.0, 0.0 to out.height.toDouble(), out.width.toDouble() to out.height.toDouble())) {
                val sx = inv.mapX(x, y)
                val sy = inv.mapY(x, y)
                assertTrue(sx >= -0.51 && sx <= 4000.51 && sy >= -0.51 && sy <= 3000.51, "corner ($x,$y) -> ($sx,$sy) at $deg deg")
            }
        }
        assertEquals(45f, Geometry().withStraighten(80f).straightenDegrees)
    }

    @Test
    fun identityMatrixCopiesPixels() {
        val src = coded(7, 5)
        val out = Raster.transform(src, Geometry())
        assertContentEquals(src.pixels, out.pixels)
        val m = PhotoGeometry.matrix(src.size, Geometry()).values
        assertContentEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), m)
    }

    @Test
    fun quarterTurnsFlipsAndCropsAreExact() {
        val src = coded(4, 3)
        val cw = Raster.transform(src, Geometry().rotateClockwise())
        assertEquals(Size(3, 4), cw.size)
        // Clockwise: the bottom-left source pixel becomes the top-left output pixel.
        for (y in 0 until 4) for (x in 0 until 3) assertEquals(src[y, 2 - x], cw[x, y])
        val flipped = Raster.transform(src, Geometry().flipHorizontal())
        for (y in 0 until 3) for (x in 0 until 4) assertEquals(src[3 - x, y], flipped[x, y])
        val vflipped = Raster.transform(src, Geometry().flipVertical())
        for (y in 0 until 3) for (x in 0 until 4) assertEquals(src[x, 2 - y], vflipped[x, y])
        val cropped = Raster.transform(src, Geometry(crop = CropRect(0.25f, 1f / 3f, 0.75f, 1f)))
        assertEquals(Size(2, 2), cropped.size)
        for (y in 0 until 2) for (x in 0 until 2) assertEquals(src[x + 1, y + 1], cropped[x, y])
    }

    @Test
    fun visibleRotateAndFlipCommuteWithRendering() {
        val src = coded(8, 6)
        val base = Geometry(crop = CropRect(0.25f, 0f, 1f, 0.5f))
        val rendered = Raster.transform(src, base)
        // Rotating the edit equals rotating the rendered result.
        val rotatedEdit = Raster.transform(src, base.rotateClockwise())
        assertContentEquals(Raster.transform(rendered, Geometry().rotateClockwise()).pixels, rotatedEdit.pixels)
        // Mirroring the edit equals mirroring the rendered result, also after a turn.
        val turned = base.rotateClockwise()
        val mirroredEdit = Raster.transform(src, turned.flipHorizontal())
        assertContentEquals(Raster.transform(Raster.transform(src, turned), Geometry().flipHorizontal()).pixels, mirroredEdit.pixels)
        val vEdit = Raster.transform(src, turned.flipVertical())
        assertContentEquals(Raster.transform(Raster.transform(src, turned), Geometry().flipVertical()).pixels, vEdit.pixels)
        // Counter-clockwise undoes clockwise.
        assertEquals(base, base.rotateClockwise().rotateCounterClockwise())
        assertEquals(base, base.flipHorizontal().flipHorizontal())
    }

    @Test
    fun mirroringAStraightenedImageMatches() {
        val src = image(40, 30) { x, y -> (0xFF shl 24) or ((x * 6) shl 16) or ((y * 8) shl 8) or ((x + y) * 3) }
        val g = Geometry().withStraighten(7f)
        val mirrored = Raster.transform(Raster.transform(src, g), Geometry().flipHorizontal())
        val viaState = Raster.transform(src, g.flipHorizontal())
        assertEquals(mirrored.size, viaState.size)
        var maxDiff = 0
        for (i in mirrored.pixels.indices) for (s in listOf(0, 8, 16)) {
            maxDiff = maxOf(maxDiff, abs(((mirrored.pixels[i] shr s) and 0xFF) - ((viaState.pixels[i] shr s) and 0xFF)))
        }
        assertTrue(maxDiff <= 2, "max channel difference $maxDiff")
    }

    @Test
    fun exifOrientationAllEightValues() {
        val upright = coded(5, 3)
        val w = 5
        val h = 3
        // Stored images built from the EXIF definitions, independently of the matrix code.
        val stored = mapOf(
            1 to upright,
            2 to image(w, h) { x, y -> upright[w - 1 - x, y] },
            3 to image(w, h) { x, y -> upright[w - 1 - x, h - 1 - y] },
            4 to image(w, h) { x, y -> upright[x, h - 1 - y] },
            5 to image(h, w) { x, y -> upright[y, x] },
            6 to image(h, w) { x, y -> upright[w - 1 - y, x] },
            7 to image(h, w) { x, y -> upright[w - 1 - y, h - 1 - x] },
            8 to image(h, w) { x, y -> upright[y, h - 1 - x] },
        )
        for ((value, s) in stored) {
            assertEquals(upright.size, ExifOrientation.uprightSize(value, s.size), "size for $value")
            val restored = Raster.orient(s, value)
            assertContentEquals(upright.pixels, restored.pixels, "orientation $value")
        }
        assertEquals(OrientationTransform(0, false), ExifOrientation.transform(0), "unknown values mean upright")
    }

    @Test
    fun aspectPresets() {
        val landscape = Size(4000, 3000)
        val c169 = AspectPreset.R16_9.centeredCrop(landscape)
        assertEquals(16.0 / 9.0, CropMath.ratioOf(c169, landscape), 0.001)
        assertEquals(1f, c169.width, 1e-4f)
        val portrait = Size(3000, 4000)
        assertEquals(3.0 / 4.0, AspectPreset.R4_3.ratioFor(portrait)!!, 1e-9)
        assertEquals(1.0, CropMath.ratioOf(AspectPreset.SQUARE.centeredCrop(portrait), portrait), 0.001)
        assertEquals(CropRect.FULL, AspectPreset.FREE.centeredCrop(landscape))
        // "Original" on a turned frame keeps the source ratio, turned.
        assertEquals(3.0 / 4.0, AspectPreset.ORIGINAL.ratioFor(portrait, landscape)!!, 1e-9)
    }

    @Test
    fun cropRectNormalisation() {
        val n = CropRect(0.9f, 1.2f, 0.1f, -0.5f).normalized()
        assertEquals(CropRect(0.1f, 0f, 0.9f, 1f), n)
        val tiny = CropRect(0.995f, 0.5f, 0.996f, 0.5f).normalized()
        assertTrue(tiny.width >= CropRect.MIN_SIZE - 1e-6f && tiny.right <= 1f)
        assertTrue(tiny.height >= CropRect.MIN_SIZE - 1e-6f)
    }

    @Test
    fun exportScaleNeverUpscales() {
        val out = Size(4000, 3000)
        assertEquals(Size(2048, 1536), PhotoExportOptions(size = PhotoExportSize.MAX_2048).targetSize(out))
        assertEquals(Size(1080, 810), PhotoExportOptions(size = PhotoExportSize.MAX_1080).targetSize(out))
        assertEquals(Size(800, 600), PhotoExportOptions(size = PhotoExportSize.MAX_4096).targetSize(Size(800, 600)))
        val scaled = Raster.transform(coded(40, 30), Geometry(), PhotoGeometry.scaleForLongEdge(Size(40, 30), 20))
        assertEquals(Size(20, 15), scaled.size)
    }
}
