#!/bin/sh
set -e

# Keep yt-dlp current on every container start: YouTube breaks old versions.
if [ "${YTDLP_AUTO_UPDATE:-1}" = "1" ]; then
  pip install --user --no-cache-dir --quiet --upgrade "yt-dlp[default]" \
    || echo "yt-dlp update failed, continuing with bundled version" >&2
fi

exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --proxy-headers \
  --forwarded-allow-ips="${FORWARDED_ALLOW_IPS:-127.0.0.1}"
