package com.example.audioconverter.audio

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

sealed interface ConversionEvent {
    data class Progress(val percent: Int, val timeMs: Long) : ConversionEvent
    data class Log(val line: String) : ConversionEvent
    data class Done(val outputUri: Uri) : ConversionEvent
    data class Failed(val reason: String) : ConversionEvent
}

object AudioConverter {

    /**
     * Convert [inputUri] (audio or video) to [format] and write the result to
     * [outputUri]. Both URIs may be SAF document URIs – ffmpeg-kit's
     * `saf:` parameter mapping is used to read/write through the
     * ContentResolver.
     */
    fun convert(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        format: OutputFormat,
    ): Flow<ConversionEvent> = callbackFlow {
        val inputPath = FFmpegKitConfig.getSafParameterForRead(context, inputUri)
        val outputPath = FFmpegKitConfig.getSafParameterForWrite(context, outputUri)
        if (inputPath == null || outputPath == null) {
            trySend(ConversionEvent.Failed("Datei konnte nicht geöffnet werden."))
            close()
            return@callbackFlow
        }

        val totalDurationMs = probeDurationMs(inputPath)

        val cmd = buildList {
            add("-y")
            add("-i"); add(inputPath)
            addAll(format.ffmpegArgs)
            add(outputPath)
        }.toTypedArray()

        val session = FFmpegKit.executeWithArgumentsAsync(
            cmd,
            { session ->
                val rc = session.returnCode
                when {
                    ReturnCode.isSuccess(rc) -> trySend(ConversionEvent.Done(outputUri))
                    ReturnCode.isCancel(rc) -> trySend(ConversionEvent.Failed("Abgebrochen"))
                    else -> {
                        val tail = session.failStackTrace ?: session.output.orEmpty().takeLast(800)
                        trySend(ConversionEvent.Failed(tail.ifBlank { "Unbekannter Fehler" }))
                    }
                }
                close()
            },
            { log -> trySend(ConversionEvent.Log(log.message)) },
            { stats: Statistics ->
                val timeMs = stats.time
                val percent = if (totalDurationMs > 0) {
                    ((timeMs.toDouble() / totalDurationMs) * 100).toInt().coerceIn(0, 100)
                } else -1
                trySend(ConversionEvent.Progress(percent, timeMs))
            },
        )

        awaitClose { FFmpegKit.cancel(session.sessionId) }
    }.flowOn(Dispatchers.IO)

    private fun probeDurationMs(path: String): Long = try {
        val info = FFprobeKit.getMediaInformation(path).mediaInformation
        val seconds = info?.duration?.toDoubleOrNull() ?: 0.0
        (seconds * 1000).toLong()
    } catch (_: Throwable) {
        0L
    }
}
