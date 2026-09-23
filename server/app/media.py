"""Downloading (yt-dlp) and processing (FFmpeg) of media files."""

from __future__ import annotations

import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from yt_dlp import YoutubeDL

from .formats import OutputFormat

ProgressFn = Callable[[float], None]

_TIME_RE = re.compile(r"^(?:(\d+):)?(?:(\d+):)?(\d+(?:[.,]\d+)?)$")


class MediaError(Exception):
    pass


@dataclass
class EditOptions:
    start: float | None = None
    end: float | None = None
    speed: float = 1.0
    normalize: bool = False


def parse_time(value: str | None) -> float | None:
    """Parse "90", "1:30", "01:01:30.5" into seconds. Empty -> None."""
    if value is None or not value.strip():
        return None
    m = _TIME_RE.match(value.strip())
    if not m:
        raise ValueError(f"Ungültige Zeitangabe: {value!r}")
    parts = [p for p in m.groups() if p is not None]
    seconds = 0.0
    for p in parts:
        seconds = seconds * 60 + float(p.replace(",", "."))
    return seconds


def download(url: str, workdir: Path, video: bool, max_bytes: int,
             on_progress: ProgressFn) -> tuple[Path, str]:
    """Download [url] with yt-dlp. Returns (file, title)."""

    def hook(d: dict) -> None:
        if d.get("status") == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            if total:
                on_progress(min(d.get("downloaded_bytes", 0) / total, 1.0))

    opts = {
        "format": "bestvideo*+bestaudio/best" if video else "bestaudio/best",
        "outtmpl": str(workdir / "source.%(ext)s"),
        "merge_output_format": "mkv",
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "max_filesize": max_bytes,
        "progress_hooks": [hook],
    }
    try:
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
    except Exception as e:  # yt-dlp raises many different error types
        raise MediaError(f"Download fehlgeschlagen: {e}") from e

    files = [p for p in workdir.glob("source.*") if not p.name.endswith(".part")]
    if not files:
        raise MediaError("Download lieferte keine Datei (evtl. zu groß?).")
    return files[0], (info or {}).get("title") or "download"


def probe_duration(path: Path) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "json", str(path)],
        capture_output=True, text=True,
    )
    try:
        return float(json.loads(out.stdout)["format"]["duration"])
    except (KeyError, ValueError, json.JSONDecodeError):
        return 0.0


def _atempo_chain(speed: float) -> list[str]:
    # atempo accepts 0.5..100 per instance; chain for slower speeds.
    filters = []
    while speed < 0.5:
        filters.append("atempo=0.5")
        speed /= 0.5
    filters.append(f"atempo={speed:.6g}")
    return filters


def build_command(src: Path, dst: Path, fmt: OutputFormat,
                  edit: EditOptions) -> list[str]:
    cmd = ["ffmpeg", "-y", "-nostdin", "-hide_banner", "-nostats",
           "-progress", "pipe:1"]
    if edit.start:
        cmd += ["-ss", f"{edit.start:.3f}"]
    if edit.end is not None:
        cmd += ["-t", f"{edit.end - (edit.start or 0):.3f}"]
    cmd += ["-i", str(src)]

    audio_filters: list[str] = []
    if edit.speed != 1.0:
        audio_filters += _atempo_chain(edit.speed)
    if edit.normalize:
        audio_filters.append("loudnorm=I=-14:TP=-1:LRA=11")
    if audio_filters:
        cmd += ["-af", ",".join(audio_filters)]

    if fmt.video:
        if edit.speed != 1.0:
            cmd += ["-vf", f"setpts=PTS/{edit.speed:.6g}"]
        cmd += ["-map", "0:v:0?", "-map", "0:a:0?"]
    else:
        cmd += ["-vn", "-map", "0:a:0"]

    cmd += list(fmt.codec_args)
    cmd += ["-f", fmt.muxer, str(dst)]
    return cmd


def convert(src: Path, dst: Path, fmt: OutputFormat, edit: EditOptions,
            on_progress: ProgressFn) -> None:
    duration = probe_duration(src)
    if edit.end is not None and duration:
        duration = min(duration, edit.end)
    if edit.start:
        duration = max(duration - edit.start, 0)
    expected = duration / edit.speed if duration else 0

    # stderr goes to a file so a chatty FFmpeg can't fill the pipe and stall.
    log_path = dst.parent / "ffmpeg.log"
    with open(log_path, "w") as log:
        proc = subprocess.Popen(
            build_command(src, dst, fmt, edit),
            stdout=subprocess.PIPE, stderr=log, text=True,
        )
        assert proc.stdout is not None
        for line in proc.stdout:
            if line.startswith("out_time_us=") and expected:
                try:
                    done = int(line.split("=", 1)[1]) / 1_000_000
                except ValueError:
                    continue
                on_progress(min(max(done / expected, 0.0), 1.0))
        exit_code = proc.wait()
    if exit_code != 0:
        tail = "\n".join(log_path.read_text(errors="replace").strip().splitlines()[-8:])
        raise MediaError(f"FFmpeg-Fehler:\n{tail}")
