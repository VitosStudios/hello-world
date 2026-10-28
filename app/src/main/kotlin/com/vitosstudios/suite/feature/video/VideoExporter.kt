package com.vitosstudios.suite.feature.video

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.common.util.Util
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import com.vitosstudios.suite.video.ExportPlan
import com.vitosstudios.suite.video.ExportPlanner
import com.vitosstudios.suite.video.ExportPreset
import com.vitosstudios.suite.video.OverlayPlan
import com.vitosstudios.suite.video.Timeline
import java.io.File

/** Maps the pure [ExportPlan] onto a Media3 Transformer composition. */
@UnstableApi
object VideoExporter {
    fun export(
        context: Context,
        timeline: Timeline,
        preset: ExportPreset,
        out: File,
        onProgress: (Float) -> Unit,
        onDone: (String?) -> Unit,
    ) {
        val plan = try {
            ExportPlanner.plan(timeline, preset)
        } catch (e: Exception) {
            onDone(e.message ?: "Nothing to export")
            return
        }
        if (out.exists()) out.delete()
        val composition = composition(plan, context)
        val handler = Handler(Looper.getMainLooper())
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(plan.videoBitrate).build())
                    .build(),
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onProgress(1f)
                    onDone(null)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    out.delete()
                    onDone(exportException.message ?: "Encoding error")
                }
            })
            .build()
        transformer.start(composition, out.absolutePath)
        val holder = ProgressHolder()
        handler.post(object : Runnable {
            override fun run() {
                val state = transformer.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) handler.postDelayed(this, 300)
            }
        })
    }

    private fun volumeProcessor(volume: Float): AudioProcessor {
        val p = ChannelMixingAudioProcessor()
        for (channels in 1..2) p.putChannelMixingMatrix(ChannelMixingMatrix.create(channels, channels).scaleBy(volume))
        return p
    }

    private fun composition(plan: ExportPlan, context: Context): Composition {
        val clips = plan.clips.map { c ->
            val item = MediaItem.Builder()
                .setUri(c.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(c.clippingStartUs / 1000)
                        .setEndPositionMs(c.clippingEndUs / 1000)
                        .build(),
                )
                .build()
            val audio = if (c.volume != 1f) listOf(volumeProcessor(c.volume)) else emptyList()
            EditedMediaItem.Builder(item).setEffects(Effects(audio, emptyList())).build()
        }
        val sequences = ArrayList<EditedMediaItemSequence>()
        sequences += EditedMediaItemSequence(clips)
        plan.music?.let { m ->
            val item = MediaItem.Builder()
                .setUri(m.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(m.clippingStartUs / 1000)
                        .setEndPositionMs(m.clippingEndUs / 1000)
                        .build(),
                )
                .build()
            val edited = EditedMediaItem.Builder(item)
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(volumeProcessor(m.volume)), emptyList()))
                .build()
            sequences += EditedMediaItemSequence(listOf(edited), m.loop)
        }
        val videoEffects = ArrayList<Effect>()
        videoEffects += Presentation.createForWidthAndHeight(plan.width, plan.height, Presentation.LAYOUT_SCALE_TO_FIT)
        if (plan.overlays.isNotEmpty()) {
            val density = context.resources.displayMetrics.scaledDensity
            val overlays = ImmutableList.builder<TextureOverlay>()
            for (o in plan.overlays) overlays.add(TimedTextOverlay(o, density))
            videoEffects += OverlayEffect(overlays.build())
        }
        return Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()
    }

    /** Text visible only between its start and end (composition time). */
    private class TimedTextOverlay(private val plan: OverlayPlan, private val density: Float) : TextOverlay() {
        private val text = SpannableString(plan.text.ifEmpty { " " }).apply {
            setSpan(AbsoluteSizeSpan((plan.fontSizeSp * density * 1.5f).toInt()), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            setSpan(ForegroundColorSpan(plan.colorArgb), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            if (plan.bold) setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }

        private fun settings(visible: Boolean): OverlaySettings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(plan.backgroundAnchorX, plan.backgroundAnchorY)
            .setOverlayFrameAnchor(plan.overlayAnchorX, plan.overlayAnchorY)
            .setAlphaScale(if (visible) 1f else 0f)
            .build()

        private val shown = settings(true)
        private val hidden = settings(false)

        override fun getText(presentationTimeUs: Long): SpannableString = text

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
            if (plan.isVisibleAt(presentationTimeUs)) shown else hidden
    }

    @Suppress("unused")
    private val keepUtil = Util::class
}
