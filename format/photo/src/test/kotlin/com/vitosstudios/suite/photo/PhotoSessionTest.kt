package com.vitosstudios.suite.photo

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoSessionTest {
    @Test
    fun discreteStepsUndoAndRedo() {
        val s = PhotoEditSession()
        s.apply { it.withGeometry(it.geometry.rotateClockwise()) }
        s.apply { it.withAdjustment(AdjustmentKind.CONTRAST, 30f) }
        s.apply { it.withGeometry(it.geometry.flipHorizontal()) }
        assertEquals(30f, s.state.adjustments.contrast)
        s.undo()
        assertFalse(s.state.geometry.flipH || s.state.geometry.flipV)
        s.undo()
        assertEquals(0f, s.state.adjustments.contrast)
        s.redo()
        assertEquals(30f, s.state.adjustments.contrast)
        s.undo(); s.undo()
        assertTrue(s.state.isIdentity)
        assertFalse(s.canUndo)
    }

    @Test
    fun sliderDragIsOneStep() {
        val s = PhotoEditSession()
        for (v in 1..50) s.drag { it.withAdjustment(AdjustmentKind.EXPOSURE, v / 50f) }
        assertEquals(1f, s.state.adjustments.exposure)
        s.endDrag()
        s.undo()
        assertTrue(s.state.isIdentity)
        s.redo()
        assertEquals(1f, s.state.adjustments.exposure)
        // Undo during an unfinished drag first commits the drag, then undoes it.
        s.drag { it.withAdjustment(AdjustmentKind.WARMTH, 40f) }
        s.undo()
        assertEquals(0f, s.state.adjustments.warmth)
        assertEquals(1f, s.state.adjustments.exposure)
    }

    @Test
    fun presetsAreUndoableAndManualChangesDropTheTag() {
        val s = PhotoEditSession()
        assertTrue(FilterPresets.ALL.size >= 9)
        assertEquals(FilterPresets.ALL.size, FilterPresets.ALL.map { it.id }.toSet().size, "unique ids")
        val vivid = FilterPresets.byId("vivid")!!
        s.apply { it.withPreset(vivid) }
        assertEquals("vivid", s.state.presetId)
        assertEquals(vivid.adjustments, s.state.adjustments)
        s.apply { it.withAdjustment(AdjustmentKind.SATURATION, 0f) }
        assertNull(s.state.presetId)
        s.undo()
        assertEquals("vivid", s.state.presetId)
        s.undo()
        assertTrue(s.state.isIdentity)
        for (p in FilterPresets.ALL) assertEquals(p.adjustments, p.adjustments.clamped(), "${p.id} within ranges")
    }

    @Test
    fun beforeAfterHelpers() {
        val st = PhotoEditState().withAdjustment(AdjustmentKind.SHARPNESS, 20f)
        assertFalse(st.isIdentity)
        assertTrue(st.withoutAdjustments().isIdentity)
        assertTrue(st.reset().isIdentity)
        assertTrue(PhotoEditState(geometry = Geometry(quarterTurns = 4)).isIdentity)
    }

    @Test
    fun exifPolicy() {
        val none = ExifPolicy.tagsToCopy(PhotoExportOptions(keepMetadata = false))
        assertTrue(none.isEmpty())
        val camera = ExifPolicy.tagsToCopy(PhotoExportOptions(keepMetadata = true))
        assertTrue("DateTimeOriginal" in camera && "Model" in camera)
        assertTrue(camera.none { it.startsWith("GPS") }, "location is opt-in")
        val withGps = ExifPolicy.tagsToCopy(PhotoExportOptions(keepMetadata = true, keepLocation = true))
        assertTrue("GPSLatitude" in withGps)
        assertTrue(withGps.none { it in ExifPolicy.NEVER_COPIED })
        assertEquals("1", ExifPolicy.tagsToWrite(Size(10, 20))[ExifPolicy.TAG_ORIENTATION])
        assertEquals(PhotoFormat.WEBP, PhotoFormat.forMimeType("image/webp"))
        assertEquals("trip-edited.png", PhotoExportOptions(format = PhotoFormat.PNG).fileName("trip.jpg"))
        assertEquals(100, PhotoExportOptions(quality = 500).validated().quality)
    }

    @Test
    fun renderEndToEndWithADecodedImage() {
        // Fixture produced with ImageIO (test only); the pipeline itself has no AWT dependency.
        val bi = BufferedImage(64, 48, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 48) for (x in 0 until 64) bi.setRGB(x, y, (0xFF shl 24) or ((x * 4) shl 16) or ((y * 5) shl 8) or 90)
        val png = ByteArrayOutputStream().also { ImageIO.write(bi, "png", it) }.toByteArray()
        val decoded = ImageIO.read(ByteArrayInputStream(png))
        val px = decoded.getRGB(0, 0, 64, 48, null, 0, 64)
        val src = ArgbImage(px, 64, 48)
        val state = PhotoEditState()
            .withGeometry(Geometry().rotateClockwise().withCrop(CropRect(0f, 0f, 1f, 0.5f)))
            .withPreset(FilterPresets.byId("warm")!!)
        val out = Raster.render(src, state)
        assertEquals(PhotoGeometry.outputSize(src.size, state.geometry), out.size)
        assertEquals(Size(48, 32), out.size)
        assertContentEquals(src.pixels, Raster.render(src, PhotoEditState()).pixels)
    }

    @Test
    fun previewDownscale() {
        val src = ArgbImage(IntArray(400 * 300) { (0xFF shl 24) or 0x336699 }, 400, 300)
        val small = Raster.downscale(src, 100)
        assertEquals(Size(100, 75), small.size)
        assertTrue(small.pixels.all { it == ((0xFF shl 24) or 0x336699) })
    }
}
