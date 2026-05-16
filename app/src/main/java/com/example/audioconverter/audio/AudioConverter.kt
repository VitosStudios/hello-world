package com.example.audioconverter.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

sealed interface ConversionEvent {
    data class Progress(val percent: Int, val timeMs: Long) : ConversionEvent
    data class Log(val line: String) : ConversionEvent
    data class Done(val outputUri: Uri) : ConversionEvent
    data class Failed(val reason: String) : ConversionEvent
}

object AudioConverter {

    /**
     * Run the bundled FFmpeg binary to convert [inputUri] to [format] and
     * write the result to [outputUri].
     *
     * The binary lives at `<nativeLibraryDir>/libffmpeg.so` (named `lib*.so`
     * so Android extracts it executable on install). It is invoked through
     * ProcessBuilder; SAF URIs are bridged into FFmpeg via `/proc/self/fd/N`
     * paths.
     *
     * The ALAC/M4A muxer needs a seekable sink so we stage it in cache and
     * copy to the output URI at the end. All other muxers stream directly.
     */
    fun convert(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        format: OutputFormat,
    ): Flow<ConversionEvent> = flow {
        val ffmpeg = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (!ffmpeg.exists()) {
            emit(ConversionEvent.Failed(
                "FFmpeg-Binary nicht gefunden (${ffmpeg.path}). " +
                "Wurde die APK für diese ABI gebaut?"
            ))
            return@flow
        }

        val resolver = context.contentResolver
        val inFd = resolver.openFileDescriptor(inputUri, "r")
        if (inFd == null) {
            emit(ConversionEvent.Failed("Quelle konnte nicht geöffnet werden."))
            return@flow
        }

        val needsSeekableSink = format.muxer == "ipod"
        val stagingFile = if (needsSeekableSink) {
            File(context.cacheDir, "stage.${format.extension}").also { it.delete() }
        } else null
        val outFd = if (!needsSeekableSink) resolver.openFileDescriptor(outputUri, "w") else null
        if (!needsSeekableSink && outFd == null) {
            inFd.close()
            emit(ConversionEvent.Failed("Ziel konnte nicht geöffnet werden."))
            return@flow
        }

        val inputArg = "/proc/self/fd/${inFd.fd}"
        val outputArg = stagingFile?.absolutePath ?: "/proc/self/fd/${outFd!!.fd}"
        val totalDurationMs = (probeDurationSeconds(context, inputUri) * 1000).toLong()

        val cmd = buildList {
            add(ffmpeg.absolutePath)
            add("-y")
            add("-nostdin")
            add("-hide_banner")
            add("-i"); add(inputArg)
            add("-vn")
            addAll(format.codecArgs)
            add("-f"); add(format.muxer)
            add(outputArg)
        }

        val proc = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            inFd.close(); outFd?.close()
            emit(ConversionEvent.Failed("Konnte FFmpeg nicht starten: ${t.message}"))
            return@flow
        }

        val progressRegex = Regex("""time=(\d+):(\d+):(\d+(?:\.\d+)?)""")
        val tail = ArrayDeque<String>()

        try {
            BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    if (tail.size >= 40) tail.removeFirst()
                    tail.addLast(line)
                    emit(ConversionEvent.Log(line))
                    progressRegex.find(line)?.let { m ->
                        val h = m.groupValues[1].toLong()
                        val mn = m.groupValues[2].toLong()
                        val s = m.groupValues[3].toDouble()
                        val ms = (((h * 60 + mn) * 60) * 1000) + (s * 1000).toLong()
                        val pct = if (totalDurationMs > 0)
                            ((ms.toDouble() / totalDurationMs) * 100).toInt().coerceIn(0, 100)
                        else -1
                        emit(ConversionEvent.Progress(pct, ms))
                    }
                }
            }

            val exit = proc.waitFor()
            if (exit != 0) {
                emit(ConversionEvent.Failed("FFmpeg exit $exit\n" + tail.joinToString("\n")))
                return@flow
            }

            if (stagingFile != null) {
                resolver.openOutputStream(outputUri, "w")?.use { sink ->
                    stagingFile.inputStream().use { it.copyTo(sink) }
                } ?: run {
                    emit(ConversionEvent.Failed("Ziel-Stream nicht beschreibbar."))
                    return@flow
                }
                stagingFile.delete()
            }

            emit(ConversionEvent.Done(outputUri))
        } finally {
            try { proc.destroy() } catch (_: Throwable) {}
            try { inFd.close() } catch (_: Throwable) {}
            try { outFd?.close() } catch (_: Throwable) {}
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Probe duration via `ffmpeg -i` — it prints `Duration: HH:MM:SS.ms` to
     * stderr and exits non-zero, which is fine for us.
     */
    private fun probeDurationSeconds(context: Context, uri: Uri): Double {
        val ffmpeg = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return 0.0
        return try {
            val proc = ProcessBuilder(
                ffmpeg.absolutePath, "-hide_banner", "-nostdin",
                "-i", "/proc/self/fd/${fd.fd}",
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""").find(out)?.let { m ->
                m.groupValues[1].toDouble() * 3600 +
                    m.groupValues[2].toDouble() * 60 +
                    m.groupValues[3].toDouble()
            } ?: 0.0
        } catch (_: Throwable) { 0.0 } finally {
            try { fd.close() } catch (_: Throwable) {}
        }
    }
}
