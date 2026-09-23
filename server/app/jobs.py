"""In-memory job queue. Each job gets its own directory under DATA_DIR/jobs."""

from __future__ import annotations

import asyncio
import re
import shutil
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path

from . import media
from .formats import OutputFormat


@dataclass
class Job:
    id: str
    fmt: OutputFormat
    edit: media.EditOptions
    workdir: Path
    url: str | None = None
    upload: Path | None = None
    title: str = "media"
    status: str = "queued"  # queued | downloading | processing | done | error
    progress: float = 0.0
    error: str | None = None
    output: Path | None = None
    created: float = field(default_factory=time.time)

    @property
    def filename(self) -> str:
        safe = re.sub(r'[\\/:*?"<>|\x00-\x1f]+', "_", self.title).strip(" ._")
        return f"{safe[:120] or 'media'}.{self.fmt.extension}"

    def public(self) -> dict:
        return {
            "id": self.id,
            "status": self.status,
            "progress": round(self.progress, 4),
            "error": self.error,
            "title": self.title,
            "filename": self.filename if self.status == "done" else None,
            "format": self.fmt.id,
            "source": self.url or "upload",
            "created": self.created,
        }


class JobManager:
    def __init__(self, data_dir: Path, max_concurrent: int, max_bytes: int,
                 ttl_seconds: int):
        self.root = data_dir / "jobs"
        self.root.mkdir(parents=True, exist_ok=True)
        self.jobs: dict[str, Job] = {}
        self.max_bytes = max_bytes
        self.ttl = ttl_seconds
        self._slots = asyncio.Semaphore(max_concurrent)
        self._tasks: set[asyncio.Task] = set()

    def new_workdir(self) -> tuple[str, Path]:
        job_id = uuid.uuid4().hex
        workdir = self.root / job_id
        workdir.mkdir()
        return job_id, workdir

    def submit(self, job: Job) -> Job:
        self.jobs[job.id] = job
        task = asyncio.create_task(self._run(job))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return job

    async def _run(self, job: Job) -> None:
        async with self._slots:
            try:
                src = job.upload
                if job.url:
                    job.status = "downloading"

                    def dl_progress(p: float) -> None:
                        job.progress = p * 0.5

                    src, job.title = await asyncio.to_thread(
                        media.download, job.url, job.workdir, job.fmt.video,
                        self.max_bytes, dl_progress,
                    )
                assert src is not None
                job.status = "processing"
                base = 0.5 if job.url else 0.0

                def conv_progress(p: float) -> None:
                    job.progress = base + p * (1 - base)

                out = job.workdir / f"output.{job.fmt.extension}"
                await asyncio.to_thread(
                    media.convert, src, out, job.fmt, job.edit, conv_progress,
                )
                src.unlink(missing_ok=True)
                job.output = out
                job.progress = 1.0
                job.status = "done"
            except media.MediaError as e:
                job.status, job.error = "error", str(e)
            except Exception as e:  # never let a job crash the worker silently
                job.status, job.error = "error", f"Interner Fehler: {e}"

    def delete(self, job_id: str) -> bool:
        job = self.jobs.pop(job_id, None)
        if job is None:
            return False
        shutil.rmtree(job.workdir, ignore_errors=True)
        return True

    async def cleanup_loop(self) -> None:
        while True:
            cutoff = time.time() - self.ttl
            for job in list(self.jobs.values()):
                if job.created < cutoff and job.status in ("done", "error"):
                    self.delete(job.id)
            # Directories left over from a previous container run.
            for d in self.root.iterdir():
                if d.name not in self.jobs and d.stat().st_mtime < cutoff:
                    shutil.rmtree(d, ignore_errors=True)
            await asyncio.sleep(600)
