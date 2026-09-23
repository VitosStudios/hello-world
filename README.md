# Media Studio

Konvertieren, schneiden und Tempo anpassen: für lokale Dateien auf dem Handy
und für Links (YouTube und alle anderen Seiten, die
[yt-dlp](https://github.com/yt-dlp/yt-dlp) kennt) über deinen eigenen Server.

| Teil | Ordner | Was es macht |
|------|--------|--------------|
| Android-App | [`android/`](android) | Lokale Dateien direkt auf dem Gerät konvertieren (eigenes FFmpeg); Links an den Server schicken und das Ergebnis speichern. |
| Server | [`server/`](server) | Web-Oberfläche + API. Lädt Links mit yt-dlp, schneidet/konvertiert mit FFmpeg. Läuft in Docker. |

## Funktionen

- **Quellen:** lokale Audio-/Videodatei, Upload im Browser, oder Link
  (in der YouTube-App einfach *Teilen → Media Studio*)
- **Schneiden:** Start/Ende als Sekunden oder `m:ss` / `h:mm:ss`
- **Tempo:** 0,25× bis 4×, Tonhöhe bleibt erhalten
- **Lautstärke normalisieren** auf −14 LUFS
- **Formate** (jeweils maximale Qualität):

| Format | Encoder | Einstellung |
|--------|---------|-------------|
| MP3 | libmp3lame | 320 kbps CBR, 48 kHz |
| WAV | pcm_s24le | 24-bit, 48 kHz |
| FLAC | flac | verlustfrei, Kompression 12 |
| AAC | aac | 320 kbps |
| OGG Vorbis | libvorbis | q=10 |
| Opus | libopus | 510 kbps Stereo |
| M4A (ALAC) | alac | verlustfrei |
| MP4 (Video, nur Server) | libx264 + aac | CRF 18, 320 kbps |

## Server installieren (Linux mit Docker, z. B. VM/LXC auf Proxmox)

```bash
git clone https://github.com/VitosStudios/hello-world.git media-studio
cd media-studio/server
cp .env.example .env
sed -i "s/^API_TOKEN=.*/API_TOKEN=$(openssl rand -hex 32)/" .env
docker compose up -d --build
grep API_TOKEN .env    # diesen Token in Browser und App eintragen
```

Danach ist die Oberfläche unter `http://<server-ip>:8000` erreichbar.
Ist das Repo privat, braucht der Server zum Klonen einen
[Deploy Key](https://docs.github.com/de/authentication/connecting-to-github-with-ssh/managing-deploy-keys)
oder einen Personal Access Token.

**Update:** `git pull && docker compose up -d --build`. yt-dlp aktualisiert
sich zusätzlich bei jedem Container-Start selbst (`YTDLP_AUTO_UPDATE=1`), weil
YouTube alte Versionen regelmäßig aussperrt.

**Von unterwegs erreichbar machen:** Den Port nicht ungeschützt ins Internet
stellen. Besser einen Reverse Proxy mit HTTPS davor setzen (Caddy,
Nginx Proxy Manager, Traefik) oder ohne offene Ports per Tailscale bzw.
Cloudflare Tunnel. Beispiel mit Caddy:

```
media.deine-domain.de {
    reverse_proxy 127.0.0.1:8000
}
```

Weitere Einstellungen (parallele Jobs, maximale Dateigröße, Aufbewahrungszeit)
stehen in [`server/.env.example`](server/.env.example).

### API

Alle Aufrufe außer `/api/health` brauchen `Authorization: Bearer <API_TOKEN>`.

| Methode | Pfad | Zweck |
|---------|------|-------|
| `GET` | `/api/formats` | verfügbare Zielformate |
| `POST` | `/api/jobs` | Auftrag anlegen (Form-Felder `url` *oder* `file`, `format`, `start`, `end`, `speed`, `normalize`) |
| `GET` | `/api/jobs` · `/api/jobs/{id}` | Status und Fortschritt |
| `GET` | `/api/jobs/{id}/download` | Ergebnis herunterladen |
| `DELETE` | `/api/jobs/{id}` | Auftrag und Dateien löschen |

Ergebnisse werden nach `JOB_TTL_HOURS` (Standard 6 h) automatisch gelöscht.

## Android-App

Jeder Push baut die APK per GitHub Actions
([`build-apk.yml`](.github/workflows/build-apk.yml)):
**Actions → letzter Lauf „Build APK“ → Artifact `MediaStudio-debug-apk`**
oder unter **Releases** (`build-<nr>`).

Beim ersten Öffnen des Link-Tabs das Zahnrad antippen und Server-Adresse
(z. B. `http://192.168.1.50:8000` oder `https://media.deine-domain.de`)
sowie den Token eintragen. Der Token wird nicht ins Cloud-Backup übernommen.

Aktuell wird nur **arm64-v8a** gebaut, das deckt alle aktuellen Android-Geräte
ab. Weitere ABIs kommen über die Matrix in `build-apk.yml` und `abiFilters` in
`android/app/build.gradle.kts` dazu.

**Lokal bauen:**

```bash
cd android
ANDROID_NDK_HOME=/pfad/zum/ndk ./scripts/build-ffmpeg.sh arm64-v8a
mkdir -p app/src/main/jniLibs/arm64-v8a
cp build/arm64-v8a/libffmpeg.so app/src/main/jniLibs/arm64-v8a/
gradle :app:assembleDebug
```

## Rechtliches

Lade nur Inhalte herunter, an denen du die Rechte hast oder die dafür
freigegeben sind. Die YouTube-Nutzungsbedingungen erlauben Downloads
grundsätzlich nur über von YouTube bereitgestellte Funktionen. Deshalb ist der
Server bewusst nur mit Token nutzbar und nicht als öffentlicher Dienst gedacht.

Gebündelte Bibliotheken der App: FFmpeg (LGPL 2.1+), LAME (LGPL),
libogg/libvorbis und Opus (BSD). Das Server-Image nutzt das FFmpeg aus Debian
(GPL, inkl. x264).
