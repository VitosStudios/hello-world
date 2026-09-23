package com.example.audioconverter.audio

import java.util.Locale

/** Trim, tempo and loudness settings shared by local and server conversions. */
data class EditOptions(
    val startSec: Double? = null,
    val endSec: Double? = null,
    val speed: Double = 1.0,
    val normalize: Boolean = false,
) {
    /** Returns a user-facing error message, or null when the options are valid. */
    fun validate(): String? = when {
        speed !in 0.25..4.0 -> "Tempo muss zwischen 0,25 und 4 liegen."
        endSec != null && endSec <= (startSec ?: 0.0) -> "Ende muss nach dem Start liegen."
        else -> null
    }

    /** FFmpeg input options (placed before `-i`) that select the trimmed range. */
    fun inputArgs(): List<String> = buildList {
        startSec?.takeIf { it > 0 }?.let { add("-ss"); add(num(it)) }
        endSec?.let { add("-t"); add(num(it - (startSec ?: 0.0))) }
    }

    /** FFmpeg `-af` filter chain, or null when no audio filter is needed. */
    fun audioFilter(): String? {
        val filters = mutableListOf<String>()
        if (speed != 1.0) {
            // atempo accepts 0.5..100 per instance; chain for slower speeds.
            var s = speed
            while (s < 0.5) {
                filters += "atempo=0.5"
                s /= 0.5
            }
            filters += "atempo=${num(s)}"
        }
        if (normalize) filters += "loudnorm=I=-14:TP=-1:LRA=11"
        return filters.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    /** Length of the produced file given the source length, both in ms. */
    fun outputDurationMs(sourceMs: Long): Long {
        if (sourceMs <= 0) return 0
        val endMs = endSec?.let { (it * 1000).toLong().coerceAtMost(sourceMs) } ?: sourceMs
        val startMs = ((startSec ?: 0.0) * 1000).toLong()
        return ((endMs - startMs).coerceAtLeast(0) / speed).toLong()
    }

    companion object {
        private val TIME = Regex("""^(?:(\d+):)?(?:(\d+):)?(\d+(?:[.,]\d+)?)$""")

        /** Parses "90", "1:30" or "1:01:30,5" into seconds; blank -> null. */
        fun parseTime(text: String): Double? {
            if (text.isBlank()) return null
            val m = TIME.matchEntire(text.trim())
                ?: throw IllegalArgumentException("Ungültige Zeitangabe: $text")
            return m.groupValues.drop(1)
                .filter { it.isNotEmpty() }
                .fold(0.0) { acc, part -> acc * 60 + part.replace(',', '.').toDouble() }
        }

        /** Always use '.' as decimal separator, whatever the device locale is. */
        fun num(value: Double): String = String.format(Locale.US, "%.3f", value)
    }
}
