"""End-to-end tests: upload a generated clip and run it through every format.

Needs ffmpeg/ffprobe on PATH. Run with:  API_TOKEN=... pytest server/tests
"""

import os
import subprocess
import time
from pathlib import Path

import pytest

TOKEN = "test-token-0123456789abcdef"
os.environ.setdefault("API_TOKEN", TOKEN)


@pytest.fixture(scope="module")
def client(tmp_path_factory):
    os.environ["DATA_DIR"] = str(tmp_path_factory.mktemp("data"))
    from fastapi.testclient import TestClient
    from app.main import app

    with TestClient(app) as c:
        c.headers["Authorization"] = f"Bearer {os.environ['API_TOKEN']}"
        yield c


@pytest.fixture(scope="module")
def clip(tmp_path_factory) -> Path:
    """10 s test video with a 440 Hz tone."""
    path = tmp_path_factory.mktemp("src") / "clip.mp4"
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error",
         "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=10",
         "-f", "lavfi", "-i", "sine=frequency=440:duration=10",
         "-c:v", "libx264", "-c:a", "aac", "-shortest", str(path)],
        check=True,
    )
    return path


def duration(path: Path) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "csv=p=0", str(path)],
        capture_output=True, text=True, check=True,
    )
    return float(out.stdout)


def run_job(client, clip: Path, **fields) -> dict:
    with clip.open("rb") as f:
        r = client.post("/api/jobs", data=fields, files={"file": ("clip.mp4", f, "video/mp4")})
    assert r.status_code == 201, r.text
    job_id = r.json()["id"]
    for _ in range(300):
        job = client.get(f"/api/jobs/{job_id}").json()
        if job["status"] in ("done", "error"):
            return job
        time.sleep(0.1)
    pytest.fail("job did not finish")


def test_requires_token(client):
    r = client.get("/api/formats", headers={"Authorization": "Bearer wrong"})
    assert r.status_code == 401


@pytest.mark.parametrize("fmt", ["mp3", "wav", "flac", "aac", "ogg", "opus", "alac", "mp4"])
def test_every_format(client, clip, tmp_path, fmt):
    job = run_job(client, clip, format=fmt)
    assert job["status"] == "done", job["error"]
    r = client.get(f"/api/jobs/{job['id']}/download")
    assert r.status_code == 200
    out = tmp_path / job["filename"]
    out.write_bytes(r.content)
    assert duration(out) == pytest.approx(10, abs=0.3)


def test_trim_and_speed(client, clip, tmp_path):
    # 2 s .. 8 s = 6 s of material, at 2x speed -> 3 s
    job = run_job(client, clip, format="mp3", start="0:02", end="8", speed="2")
    assert job["status"] == "done", job["error"]
    out = tmp_path / "out.mp3"
    out.write_bytes(client.get(f"/api/jobs/{job['id']}/download").content)
    assert duration(out) == pytest.approx(3, abs=0.2)


def test_video_speed(client, clip, tmp_path):
    job = run_job(client, clip, format="mp4", speed="0.5", normalize="true")
    assert job["status"] == "done", job["error"]
    out = tmp_path / "out.mp4"
    out.write_bytes(client.get(f"/api/jobs/{job['id']}/download").content)
    assert duration(out) == pytest.approx(20, abs=0.3)


def test_rejects_bad_input(client, clip):
    assert client.post("/api/jobs", data={"format": "mp3"}).status_code == 400
    assert client.post("/api/jobs", data={"url": "file:///etc/passwd"}).status_code == 400
    assert client.post("/api/jobs", data={"url": "https://x.y", "format": "exe"}).status_code == 400
    assert client.post("/api/jobs", data={"url": "https://x.y", "speed": "10"}).status_code == 400
    assert client.post("/api/jobs", data={"url": "https://x.y", "start": "5", "end": "2"}).status_code == 400
