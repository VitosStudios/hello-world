package com.vitosstudios.suite.video

/**
 * Everything the app needs to build a Media3 `Composition`, with no further logic:
 *
 * - one `EditedMediaItemSequence` of [clips]: `MediaItem.Builder().setUri(uri)
 *   .setClippingConfiguration(ClippingConfiguration.Builder().setStartPositionUs(clippingStartUs)
 *   .setEndPositionUs(clippingEndUs))`, with a volume audio processor when [ClipPlan.volume] != 1;
 * - an optional second, audio-only sequence for [music] (`isLooping = music.loop`);
 * - composition video effects: `Presentation.createForWidthAndHeight(width, height,
 *   LAYOUT_SCALE_TO_FIT)` and one `OverlayEffect` holding a `TextOverlay` per [overlays] entry,
 *   visible while `startUs <= presentationTimeUs < endUs` (composition time);
 * - encoder settings from [videoBitrate] and [audioBitrate].
 */
data class ExportPlan(
    val width: Int,
    val height: Int,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val durationUs: Long,
    val clips: List<ClipPlan>,
    val music: MusicPlan?,
    val overlays: List<OverlayPlan>,
)

data class ClipPlan(
    val uri: String,
    val clippingStartUs: Long,
    val clippingEndUs: Long,
    /** Where the clip starts in the output. */
    val timelineStartUs: Long,
    /** Gain for the clip's own audio, 0 when muted. Audio is never removed so every item has an audio track. */
    val volume: Float,
)

data class MusicPlan(
    val uri: String,
    val clippingStartUs: Long,
    /** End inside the music file; for non-looping music it stops with the video. */
    val clippingEndUs: Long,
    val loop: Boolean,
    val volume: Float,
)

/**
 * A text overlay in Media3 terms. Anchors are normalised device coordinates (-1..1, y up), as used
 * by `StaticOverlaySettings.Builder.setBackgroundFrameAnchor` (position on the video frame) and
 * `setOverlayFrameAnchor` (point of the text box placed there).
 */
data class OverlayPlan(
    val text: String,
    val fontSizeSp: Float,
    val colorArgb: Int,
    val bold: Boolean,
    val startUs: Long,
    val endUs: Long,
    val backgroundAnchorX: Float,
    val backgroundAnchorY: Float,
    val overlayAnchorX: Float,
    val overlayAnchorY: Float,
) {
    fun isVisibleAt(presentationTimeUs: Long): Boolean = presentationTimeUs in startUs until endUs
}

object ExportPlanner {
    /** Builds the export description; fails for an empty timeline. */
    fun plan(timeline: Timeline, preset: ExportPreset = timeline.preset): ExportPlan {
        require(timeline.clips.isNotEmpty()) { "Nothing to export" }
        val clips = ArrayList<ClipPlan>()
        var t = 0L
        for (c in timeline.clips) {
            clips += ClipPlan(c.sourceUri, c.inMs * 1000, c.outMs * 1000, t * 1000, c.effectiveVolume)
            t += c.durationMs
        }
        val durationMs = timeline.durationMs
        val music = timeline.music?.let { m ->
            val end = if (m.loop) m.durationMs else minOf(m.durationMs, m.offsetMs + durationMs)
            MusicPlan(m.uri, m.offsetMs * 1000, end * 1000, m.loop, m.volume)
        }
        val overlays = timeline.clampOverlays().overlays.map { o ->
            OverlayPlan(
                text = o.text,
                fontSizeSp = o.fontSizeSp,
                colorArgb = o.colorArgb,
                bold = o.bold,
                startUs = o.startMs * 1000,
                endUs = o.endMs * 1000,
                backgroundAnchorX = o.x * 2 - 1,
                backgroundAnchorY = 1 - o.y * 2,
                overlayAnchorX = o.anchor.fx * 2 - 1,
                overlayAnchorY = 1 - o.anchor.fy * 2,
            )
        }
        return ExportPlan(preset.width, preset.height, preset.videoBitrate, preset.audioBitrate, durationMs * 1000, clips, music, overlays)
    }

    /** Suggested output file name, e.g. "holiday-720p.mp4". */
    fun fileName(baseName: String, preset: ExportPreset): String {
        val base = baseName.substringBeforeLast('.', baseName).ifBlank { "video" }
        val suffix = when (preset) {
            ExportPreset.P1080 -> "1080p"
            ExportPreset.P720 -> "720p"
            ExportPreset.VERTICAL_9_16 -> "vertical"
        }
        return "$base-$suffix.mp4"
    }
}
