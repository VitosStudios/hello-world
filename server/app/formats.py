"""Output formats and their FFmpeg encoder settings (max quality per codec)."""

from dataclasses import dataclass


@dataclass(frozen=True)
class OutputFormat:
    id: str
    label: str
    extension: str
    mime: str
    muxer: str
    video: bool
    codec_args: tuple[str, ...]
    description: str

    def public(self) -> dict:
        return {
            "id": self.id,
            "label": self.label,
            "extension": self.extension,
            "video": self.video,
            "description": self.description,
        }


_ALL = [
    OutputFormat(
        "mp3", "MP3", "mp3", "audio/mpeg", "mp3", False,
        ("-c:a", "libmp3lame", "-b:a", "320k", "-ar", "48000", "-ac", "2",
         "-id3v2_version", "3"),
        "320 kbps CBR, 48 kHz",
    ),
    OutputFormat(
        "wav", "WAV", "wav", "audio/wav", "wav", False,
        ("-c:a", "pcm_s24le", "-ar", "48000", "-ac", "2"),
        "24-bit PCM, 48 kHz, unkomprimiert",
    ),
    OutputFormat(
        "flac", "FLAC", "flac", "audio/flac", "flac", False,
        ("-c:a", "flac", "-compression_level", "12", "-sample_fmt", "s32",
         "-ar", "48000"),
        "Verlustfrei, 24-bit",
    ),
    OutputFormat(
        "aac", "AAC", "m4a", "audio/mp4", "ipod", False,
        ("-c:a", "aac", "-b:a", "320k", "-ar", "48000", "-ac", "2",
         "-movflags", "+faststart"),
        "320 kbps AAC-LC in M4A",
    ),
    OutputFormat(
        "ogg", "OGG Vorbis", "ogg", "audio/ogg", "ogg", False,
        ("-c:a", "libvorbis", "-q:a", "10", "-ar", "48000", "-ac", "2"),
        "Vorbis q=10, höchste VBR-Stufe",
    ),
    OutputFormat(
        "opus", "Opus", "opus", "audio/opus", "opus", False,
        ("-c:a", "libopus", "-b:a", "510k", "-vbr", "on",
         # 510k is only valid for 2 channels, so force stereo.
         "-application", "audio", "-ar", "48000", "-ac", "2"),
        "510 kbps, Codec-Maximum",
    ),
    OutputFormat(
        "alac", "M4A (ALAC)", "m4a", "audio/mp4", "ipod", False,
        ("-c:a", "alac", "-ar", "48000", "-movflags", "+faststart"),
        "Apple Lossless, verlustfrei",
    ),
    OutputFormat(
        "mp4", "MP4 (Video)", "mp4", "video/mp4", "mp4", True,
        ("-c:v", "libx264", "-preset", "medium", "-crf", "18",
         "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "320k",
         "-ar", "48000", "-movflags", "+faststart"),
        "H.264 CRF 18 + AAC 320 kbps",
    ),
]

FORMATS: dict[str, OutputFormat] = {f.id: f for f in _ALL}
