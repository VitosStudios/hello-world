package com.example.audioconverter.audio

/**
 * Output audio formats with FFmpeg CLI args tuned for maximum quality.
 *
 *  - MP3 : 320 kbps CBR, 48 kHz, joint stereo
 *  - WAV : 24-bit signed PCM little-endian, 48 kHz
 *  - FLAC: lossless, compression level 12 (max), 24-bit
 *  - AAC : 320 kbps via the bundled aac encoder (raw ADTS)
 *  - OGG : Vorbis, q=10 (highest VBR setting)
 *  - OPUS: 510 kbps (codec ceiling), music-tuned
 *  - M4A : ALAC lossless (Apple Lossless) in an MP4 container
 *
 * Because the output is written through a `/proc/self/fd/N` pipe-style path,
 * FFmpeg can't infer the muxer from the filename — [muxer] is passed via
 * `-f` explicitly. The `-movflags +faststart` style flags are NOT used for
 * fd output because fd sinks aren't seekable.
 */
enum class OutputFormat(
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val muxer: String,
    val codecArgs: List<String>,
    val description: String,
) {
    MP3(
        displayName = "MP3",
        extension = "mp3",
        mimeType = "audio/mpeg",
        muxer = "mp3",
        codecArgs = listOf(
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
        muxer = "wav",
        codecArgs = listOf(
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
        muxer = "flac",
        codecArgs = listOf(
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
        muxer = "adts",
        codecArgs = listOf(
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
        muxer = "ogg",
        codecArgs = listOf(
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
        muxer = "opus",
        codecArgs = listOf(
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
        // ipod muxer is mp4 tuned for iTunes-compatible m4a files, but it
        // requires a seekable output. For our non-seekable fd sink we have
        // to fall back to streamable matroska/webm — none of those store
        // ALAC. Instead we write to a temp file (handled in AudioConverter)
        // when this format is selected. Muxer stays "ipod" so AudioConverter
        // knows what to do.
        muxer = "ipod",
        codecArgs = listOf(
            "-c:a", "alac",
            "-ar", "48000",
        ),
        description = "Apple Lossless – verlustfrei",
    ),
}
