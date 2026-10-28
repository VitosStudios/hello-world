package com.vitosstudios.suite.photo

/**
 * Crop rectangle normalised to 0..1 of the *transformed frame*: the image after flips, quarter
 * turns and the straighten step (which already removes the empty corners). This is the frame the
 * crop overlay is drawn on, so the UI never converts coordinates.
 */
data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isFull: Boolean get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f

    /** Clamped to 0..1, corners ordered, with a minimum size of [MIN_SIZE] in each direction. */
    fun normalized(): CropRect {
        var l = left.coerceIn(0f, 1f)
        var t = top.coerceIn(0f, 1f)
        var r = right.coerceIn(0f, 1f)
        var b = bottom.coerceIn(0f, 1f)
        if (r < l) { val x = l; l = r; r = x }
        if (b < t) { val x = t; t = b; b = x }
        if (r - l < MIN_SIZE) { if (l + MIN_SIZE <= 1f) r = l + MIN_SIZE else l = r - MIN_SIZE }
        if (b - t < MIN_SIZE) { if (t + MIN_SIZE <= 1f) b = t + MIN_SIZE else t = b - MIN_SIZE }
        return CropRect(l, t, r, b)
    }

    /** The same region after the frame is turned 90 degrees clockwise. */
    fun rotatedClockwise(): CropRect = CropRect(1f - bottom, left, 1f - top, right)

    /** The same region after the frame is turned 90 degrees counter-clockwise. */
    fun rotatedCounterClockwise(): CropRect = CropRect(top, 1f - right, bottom, 1f - left)

    fun mirroredHorizontally(): CropRect = CropRect(1f - right, top, 1f - left, bottom)
    fun mirroredVertically(): CropRect = CropRect(left, 1f - bottom, right, 1f - top)

    companion object {
        val FULL = CropRect()
        const val MIN_SIZE = 0.02f
    }
}

/**
 * Geometric edits, applied in this order to the upright source image:
 * flips (about the centre), [quarterTurns] clockwise quarter turns, [straightenDegrees] clockwise
 * rotation with an automatic inscribed crop (no empty corners), then [crop].
 */
data class Geometry(
    val crop: CropRect = CropRect.FULL,
    /** 0..3 clockwise quarter turns. */
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    /** -45..45 degrees, positive is clockwise. */
    val straightenDegrees: Float = 0f,
) {
    val isIdentity: Boolean
        get() = crop.isFull && quarterTurns.mod(4) == 0 && !flipH && !flipV && straightenDegrees == 0f

    /** Turns what the user sees 90 degrees clockwise (the crop turns with it). */
    fun rotateClockwise(): Geometry = copy(quarterTurns = (quarterTurns + 1).mod(4), crop = crop.rotatedClockwise())

    fun rotateCounterClockwise(): Geometry = copy(quarterTurns = (quarterTurns + 3).mod(4), crop = crop.rotatedCounterClockwise())

    /**
     * Mirrors what the user sees left-to-right. Flips are applied before the quarter turns, so
     * with an odd number of turns the visible horizontal mirror is a vertical flip of the source;
     * the straighten angle changes sign because the mirror reverses its direction.
     */
    fun flipHorizontal(): Geometry =
        if (quarterTurns.mod(2) == 0) copy(flipH = !flipH, straightenDegrees = -straightenDegrees, crop = crop.mirroredHorizontally())
        else copy(flipV = !flipV, straightenDegrees = -straightenDegrees, crop = crop.mirroredHorizontally())

    /** Mirrors what the user sees top-to-bottom. */
    fun flipVertical(): Geometry =
        if (quarterTurns.mod(2) == 0) copy(flipV = !flipV, straightenDegrees = -straightenDegrees, crop = crop.mirroredVertically())
        else copy(flipH = !flipH, straightenDegrees = -straightenDegrees, crop = crop.mirroredVertically())

    fun withStraighten(degrees: Float): Geometry = copy(straightenDegrees = degrees.coerceIn(-MAX_STRAIGHTEN, MAX_STRAIGHTEN))

    fun withCrop(rect: CropRect): Geometry = copy(crop = rect.normalized())

    companion object {
        const val MAX_STRAIGHTEN = 45f
    }
}

/**
 * Tone and colour adjustments. Ranges: [exposure] -2..2 EV; [contrast], [highlights], [shadows],
 * [saturation], [warmth] -100..100; [sharpness] 0..100. Zero everywhere is the identity.
 */
data class Adjustments(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val sharpness: Float = 0f,
) {
    val isIdentity: Boolean
        get() = exposure == 0f && contrast == 0f && highlights == 0f && shadows == 0f &&
            saturation == 0f && warmth == 0f && sharpness == 0f

    /** Every value clamped to its documented range. */
    fun clamped(): Adjustments = Adjustments(
        exposure = exposure.coerceIn(AdjustmentKind.EXPOSURE.min, AdjustmentKind.EXPOSURE.max),
        contrast = contrast.coerceIn(-100f, 100f),
        highlights = highlights.coerceIn(-100f, 100f),
        shadows = shadows.coerceIn(-100f, 100f),
        saturation = saturation.coerceIn(-100f, 100f),
        warmth = warmth.coerceIn(-100f, 100f),
        sharpness = sharpness.coerceIn(0f, 100f),
    )

    /** Returns a copy with one value changed (clamped to its range). */
    fun with(kind: AdjustmentKind, value: Float): Adjustments = when (kind) {
        AdjustmentKind.EXPOSURE -> copy(exposure = value)
        AdjustmentKind.CONTRAST -> copy(contrast = value)
        AdjustmentKind.HIGHLIGHTS -> copy(highlights = value)
        AdjustmentKind.SHADOWS -> copy(shadows = value)
        AdjustmentKind.SATURATION -> copy(saturation = value)
        AdjustmentKind.WARMTH -> copy(warmth = value)
        AdjustmentKind.SHARPNESS -> copy(sharpness = value)
    }.clamped()

    operator fun get(kind: AdjustmentKind): Float = when (kind) {
        AdjustmentKind.EXPOSURE -> exposure
        AdjustmentKind.CONTRAST -> contrast
        AdjustmentKind.HIGHLIGHTS -> highlights
        AdjustmentKind.SHADOWS -> shadows
        AdjustmentKind.SATURATION -> saturation
        AdjustmentKind.WARMTH -> warmth
        AdjustmentKind.SHARPNESS -> sharpness
    }

    companion object {
        val NONE = Adjustments()
    }
}

/** Slider metadata for the adjustment sheet (label, range, step). Neutral value is 0 for all. */
enum class AdjustmentKind(val label: String, val min: Float, val max: Float, val step: Float) {
    EXPOSURE("Exposure", -2f, 2f, 0.05f),
    CONTRAST("Contrast", -100f, 100f, 1f),
    HIGHLIGHTS("Highlights", -100f, 100f, 1f),
    SHADOWS("Shadows", -100f, 100f, 1f),
    SATURATION("Saturation", -100f, 100f, 1f),
    WARMTH("Warmth", -100f, 100f, 1f),
    SHARPNESS("Sharpness", 0f, 100f, 1f),
}

/**
 * The complete, immutable edit of one photo. Kept in a
 * [com.vitosstudios.suite.common.HistoryStack] for unlimited undo/redo; the source pixels are
 * never modified.
 */
data class PhotoEditState(
    val geometry: Geometry = Geometry(),
    val adjustments: Adjustments = Adjustments.NONE,
    /** Id of the last applied [FilterPreset], or null once sliders were changed by hand. */
    val presetId: String? = null,
) {
    /** True when the edit changes nothing (the "before" image equals the "after" image). */
    val isIdentity: Boolean get() = geometry.isIdentity && adjustments.isIdentity

    fun withGeometry(g: Geometry): PhotoEditState = copy(geometry = g)

    /** Sets one slider; the preset tag is dropped because the look no longer matches it. */
    fun withAdjustment(kind: AdjustmentKind, value: Float): PhotoEditState =
        copy(adjustments = adjustments.with(kind, value), presetId = null)

    fun withAdjustments(a: Adjustments): PhotoEditState = copy(adjustments = a.clamped(), presetId = null)

    fun withPreset(preset: FilterPreset): PhotoEditState = copy(adjustments = preset.adjustments.clamped(), presetId = preset.id)

    /** The "before" state used by the comparison view: same geometry, no colour changes. */
    fun withoutAdjustments(): PhotoEditState = copy(adjustments = Adjustments.NONE, presetId = null)

    fun reset(): PhotoEditState = PhotoEditState()
}
