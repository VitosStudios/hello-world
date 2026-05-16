# Audio Converter (Android)

Konvertiert Video- und Audiodateien in andere Audioformate – in jeweils
maximaler Qualität der Codecs. FFmpeg läuft komplett auf dem Gerät; es werden
keine Daten hochgeladen.

## Unterstützte Zielformate

| Format     | Encoder      | Qualität                              |
|------------|--------------|---------------------------------------|
| MP3        | libmp3lame   | 320 kbps CBR, 48 kHz                  |
| WAV        | pcm_s24le    | 24-bit PCM, 48 kHz                    |
| FLAC       | flac         | Lossless, 24-bit, Kompression 12      |
| AAC        | aac          | 320 kbps AAC-LC                       |
| OGG Vorbis | libvorbis    | q=10 (höchste VBR-Stufe)              |
| Opus       | libopus      | 510 kbps, music-tuned                 |
| M4A (ALAC) | alac         | Apple Lossless                        |

Quellen: beliebige Audio- oder Videodateien, die das System-Pickerdialog
auswählen lässt (mp4, mkv, mov, webm, mp3, wav, m4a, flac, ogg, …).

## APK herunterladen

Jeder Push auf `claude/**` / `master` triggert den
[`Build APK`](.github/workflows/build-apk.yml)-Workflow. Die fertige APK liegt
danach unter:

* **Actions → letzter Run → Artifact `AudioConverter-debug-apk`**
* Oder als Pre-Release unter **Releases** (Tag `build-<runNr>`).

## Lokal bauen

```bash
gradle wrapper --gradle-version 8.7
./gradlew :app:assembleDebug
# APK liegt in app/build/outputs/apk/debug/
```

Benötigt JDK 17 und das Android SDK (Plattform 34, Build-Tools 34.0.0).

## Architektur

* `MainActivity` – Compose-Einstieg.
* `ui/ConverterScreen.kt` – Picker, Formatauswahl, Fortschritt.
* `audio/Format.kt` – Format-Enum mit FFmpeg-Argumenten je Codec.
* `audio/AudioConverter.kt` – führt FFmpeg via
  [`ffmpeg-kit-full`](https://github.com/arthenica/ffmpeg-kit) aus und
  streamt Fortschritts-Events als Flow.

Storage Access Framework wird sowohl für Input (`OpenDocument`) als auch für
Output (`CreateDocument`) genutzt – die App braucht daher keinerlei Storage-
Permissions zur Laufzeit.
