"""Media Studio server: download (yt-dlp), trim, re-time and convert (FFmpeg)."""

from __future__ import annotations

import asyncio
import hmac
import os
import shutil
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from . import media
from .formats import FORMATS
from .jobs import Job, JobManager

API_TOKEN = os.environ.get("API_TOKEN", "")
if len(API_TOKEN) < 16:
    raise RuntimeError("API_TOKEN muss gesetzt sein (mindestens 16 Zeichen).")

DATA_DIR = Path(os.environ.get("DATA_DIR", "/data"))
MAX_BYTES = int(os.environ.get("MAX_FILE_MB", "4096")) * 1024 * 1024
STATIC_DIR = Path(__file__).parent / "static"

manager = JobManager(
    DATA_DIR,
    max_concurrent=int(os.environ.get("MAX_CONCURRENT_JOBS", "2")),
    max_bytes=MAX_BYTES,
    ttl_seconds=int(float(os.environ.get("JOB_TTL_HOURS", "6")) * 3600),
)


@asynccontextmanager
async def lifespan(_: FastAPI):
    cleanup = asyncio.create_task(manager.cleanup_loop())
    yield
    cleanup.cancel()


app = FastAPI(title="Media Studio", lifespan=lifespan, docs_url=None, redoc_url=None)
_bearer = HTTPBearer(auto_error=False)


def require_token(creds: HTTPAuthorizationCredentials | None = Depends(_bearer)) -> None:
    if creds is None or not hmac.compare_digest(creds.credentials.encode(), API_TOKEN.encode()):
        raise HTTPException(401, "Nicht autorisiert", headers={"WWW-Authenticate": "Bearer"})


def _get_job(job_id: str) -> Job:
    job = manager.jobs.get(job_id)
    if job is None:
        raise HTTPException(404, "Job nicht gefunden")
    return job


@app.get("/api/health")
def health() -> dict:
    return {"ok": True}


@app.get("/api/formats", dependencies=[Depends(require_token)])
def formats() -> list[dict]:
    return [f.public() for f in FORMATS.values()]


@app.get("/api/jobs", dependencies=[Depends(require_token)])
def list_jobs() -> list[dict]:
    return [j.public() for j in sorted(manager.jobs.values(), key=lambda j: -j.created)]


@app.post("/api/jobs", dependencies=[Depends(require_token)], status_code=201)
async def create_job(
    url: str | None = Form(None),
    file: UploadFile | None = File(None),
    format: str = Form("mp3"),
    start: str | None = Form(None),
    end: str | None = Form(None),
    speed: float = Form(1.0),
    normalize: bool = Form(False),
) -> dict:
    fmt = FORMATS.get(format)
    if fmt is None:
        raise HTTPException(400, f"Unbekanntes Format: {format}")
    url = (url or "").strip() or None
    if (url is None) == (file is None or not file.filename):
        raise HTTPException(400, "Entweder einen Link oder eine Datei angeben.")
    if url and not url.startswith(("http://", "https://")):
        raise HTTPException(400, "Link muss mit http:// oder https:// beginnen.")
    try:
        edit = media.EditOptions(
            start=media.parse_time(start),
            end=media.parse_time(end),
            speed=speed,
            normalize=normalize,
        )
    except ValueError as e:
        raise HTTPException(400, str(e)) from e
    if not 0.25 <= edit.speed <= 4.0:
        raise HTTPException(400, "Tempo muss zwischen 0.25 und 4.0 liegen.")
    if edit.end is not None and edit.end <= (edit.start or 0):
        raise HTTPException(400, "Ende muss nach dem Start liegen.")

    job_id, workdir = manager.new_workdir()
    job = Job(id=job_id, fmt=fmt, edit=edit, workdir=workdir, url=url)

    if file is not None and file.filename:
        dest = workdir / "source"
        written = 0
        with dest.open("wb") as out:
            while chunk := await file.read(1024 * 1024):
                written += len(chunk)
                if written > MAX_BYTES:
                    shutil.rmtree(workdir, ignore_errors=True)
                    raise HTTPException(413, "Datei zu groß.")
                out.write(chunk)
        job.upload = dest
        job.title = Path(file.filename).stem or "upload"

    return manager.submit(job).public()


@app.get("/api/jobs/{job_id}", dependencies=[Depends(require_token)])
def get_job(job_id: str) -> dict:
    return _get_job(job_id).public()


@app.get("/api/jobs/{job_id}/download", dependencies=[Depends(require_token)])
def download(job_id: str) -> FileResponse:
    job = _get_job(job_id)
    if job.status != "done" or job.output is None:
        raise HTTPException(409, "Job ist noch nicht fertig.")
    return FileResponse(job.output, media_type=job.fmt.mime, filename=job.filename)


@app.delete("/api/jobs/{job_id}", dependencies=[Depends(require_token)], status_code=204)
def delete_job(job_id: str) -> None:
    if not manager.delete(job_id):
        raise HTTPException(404, "Job nicht gefunden")


@app.get("/", include_in_schema=False)
def index() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")
