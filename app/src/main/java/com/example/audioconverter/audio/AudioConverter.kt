package com.example.audioconverter.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

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
     * so Android extracts it executable on install) and runs as a child
     * process. SAF file descriptors are opened with O_CLOEXEC and therefore
     * do not survive into the child, so `/proc/self/fd/N` can't be used.
     * Instead the input is staged into the app cache, FFmpeg works on real
     * file paths (which also keeps every muxer seekable), and the result is
     * copied to [outputUri] at the end.
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

        val workDir = File(context.cacheDir, "convert").apply {
            deleteRecursively()
            mkdirs()
        }
        val inputFile = File(workDir, "input")
        val outputFile = File(workDir, "output.${format.extension}")
        val resolver = context.contentResolver

        try {
            val copied = resolver.openInputStream(inputUri)?.use { src ->
                inputFile.outputStream().use { src.copyTo(it) }
            }
            if (copied == null) {
                emit(ConversionEvent.Failed("Quelle konnte nicht geöffnet werden."))
                return@flow
            }

            val totalDurationMs = (probeDurationSeconds(ffmpeg, inputFile) * 1000).toLong()

            val cmd = buildList {
                add(ffmpeg.absolutePath)
                add("-y")
                add("-nostdin")
                add("-hide_banner")
                add("-i"); add(inputFile.absolutePath)
                add("-vn")
                addAll(format.codecArgs)
                add("-f"); add(format.muxer)
                add(outputFile.absolutePath)
            }

            val proc = try {
                ProcessBuilder(cmd).redirectErrorStream(true).start()
            } catch (t: Throwable) {
                emit(ConversionEvent.Failed("Konnte FFmpeg nicht starten: ${t.message}"))
                return@flow
            }

            val progressRegex = Regex("""time=(\d+):(\d+):(\d+(?:\.\d+)?)""")
            val tail = ArrayDeque<String>()

            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        currentCoroutineContext().ensureActive()
                        if (tail.size >= 40) tail.removeFirst()
                        tail.addLast(line)
                        emit(ConversionEvent.Log(line))
                        progressRegex.find(line)?.let { m ->
                            val h = m.groupValues[1].toLong()
                            val mn = m.groupValues[2].toLong()
                            val s = m.groupValues[3].toDouble()
                            val ms = ((h * 60 + mn) * 60) * 1000 + (s * 1000).toLong()
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
            } finally {
                proc.destroy()
            }

            val written = resolver.openOutputStream(outputUri, "wt")?.use { sink ->
                outputFile.inputStream().use { it.copyTo(sink) }
            }
            if (written == null) {
                emit(ConversionEvent.Failed("Ziel-Datei nicht beschreibbar."))
                return@flow
            }

            emit(ConversionEvent.Done(outputUri))
        } finally {
            workDir.deleteRecursively()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Probe duration via `ffmpeg -i` — it prints `Duration: HH:MM:SS.ms` to
     * stderr and exits non-zero, which is fine for us.
     */
    private fun probeDurationSeconds(ffmpeg: File, input: File): Double = try {
        val proc = ProcessBuilder(
            ffmpeg.absolutePath, "-hide_banner", "-nostdin", "-i", input.absolutePath,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""").find(out)?.let { m ->
            m.groupValues[1].toDouble() * 3600 +
                m.groupValues[2].toDouble() * 60 +
                m.groupValues[3].toDouble()
        } ?: 0.0
    } catch (_: Throwable) {
        0.0
    }
}
