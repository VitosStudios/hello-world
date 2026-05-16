package com.example.audioconverter.audio

/**
 * Output audio formats with FFmpeg encoder args tuned for maximum quality.
 *
 *  - MP3 : 320 kbps CBR, 48 kHz, joint stereo
 *  - WAV : 24-bit signed PCM little-endian, 48 kHz
 *  - FLAC: lossless, compression level 12 (max), 24-bit
 *  - AAC : 320 kbps via the bundled aac encoder
 *  - OGG : Vorbis, q=10 (highest VBR setting)
 *  - OPUS: 510 kbps (codec ceiling), VOIP-off, music tuning
 *  - M4A : ALAC lossless (Apple Lossless)
 */
enum class OutputFormat(
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val ffmpegArgs: List<String>,
    val description: String,
) {
    MP3(
        displayName = "MP3",
        extension = "mp3",
        mimeType = "audio/mpeg",
        ffmpegArgs = listOf(
            "-vn",
            "-c:a", "libmp3lame",
            "-b:a", "320k",
            "-ar", "48000",
            "-ac", "2",
            "-id3v2_version", "3",
        ),
        description = "320 kbps CBR, 48 kHz – höchste MP3-Qualität",
    ),
    WAV(
        displayName = "WAV",
        extension = "wav",
        mimeType = "audio/wav",
        ffmpegArgs = listOf(
            "-vn",
            "-c:a", "pcm_s24le",
            "-ar", "48000",
            "-ac", "2",
        ),
        description = "24-bit PCM, 48 kHz – unkomprimiert",
    ),
    FLAC(
        displayName = "FLAC",
        extension = "flac",
        mimeType = "audio/flac",
        ffmpegArgs = listOf(
            "-vn",
            "-c:a", "flac",
            "-compression_level", "12",
            "-sample_fmt", "s32",
            "-ar", "48000",
        ),
        description = "Lossless, 24-bit, max. Kompression",
    ),
    AAC(
        displayName = "AAC",
        extension = "aac",
        mimeType = "audio/aac",
        ffmpegArgs = listOf(
            "-vn",
            "-c:a", "aac",
            "-b:a", "320k",
            "-ar", "48000",
            "-ac", "2",
        ),
        description = "320 kbps AAC-LC",
    ),
    OGG(
        displayName = "OGG Vorbis",
        extension = "ogg",
        mimeType = "audio/ogg",
        ffmpegArgs = listOf(
            "-vn",
            "-c:a", "libvorbis",
            "-q:a", "10",
            "-ar", "48000",
            "-ac", "2",
        ),
        description = "Vorbis q=10 – höchste VBR-Stufe",
    ),
    OPUS(
        displayName = "Opus",
        extension = "opus",
        mimeType = "audio/opus",
        ffmpegArgs = listOf(
            "-vn",
            "-c:a", "libopus",
            "-b:a", "510k",
            "-vbr", "on",
            "-application", "audio",
            "-ar", "48000",
        ),
        description = "510 kbps, music-tuned – Codec-Maximum",
    ),
    M4A_ALAC(
        displayName = "M4A (ALAC)",
        extension = "m4a",
        mimeType = "audio/mp4",
        ffmpegArgs = listOf(
            "-vn",
            "-c:a", "alac",
            "-ar", "48000",
        ),
        description = "Apple Lossless – verlustfrei",
    ),
}
