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
| AAC        | aac          | 320 kbps AAC-LC (ADTS)                |
| OGG Vorbis | libvorbis    | q=10 (höchste VBR-Stufe)              |
| Opus       | libopus      | 510 kbps, music-tuned                 |
| M4A (ALAC) | alac         | Apple Lossless                        |

Quellen: beliebige Audio- oder Videodateien, die der System-Picker auswählen
lässt (mp4, mkv, mov, webm, mp3, wav, m4a, flac, ogg, …).

## APK herunterladen

Jeder Push auf `claude/**` / `master` triggert den
[`Build APK`](.github/workflows/build-apk.yml)-Workflow. Die fertige APK liegt
danach unter:

* **Actions → letzter Run → Artifact `AudioConverter-debug-apk`**
* Oder als Pre-Release unter **Releases** (Tag `build-<runNr>`).

Aktuell wird nur **arm64-v8a** gebaut (alle aktuellen Android-Geräte). Weitere
ABIs (armeabi-v7a, x86_64) können in `.github/workflows/build-apk.yml` zur
Matrix hinzugefügt werden – pro neuer ABI dauert der erste CI-Lauf ~30 Min.,
spätere Läufe sind durch den Cache <2 Min.

## Architektur

* `MainActivity` + `ui/ConverterScreen.kt` – Compose-UI: Picker,
  Formatauswahl, Fortschritt.
* `audio/Format.kt` – Format-Enum mit FFmpeg-CLI-Argumenten je Codec.
* `audio/AudioConverter.kt` – startet die FFmpeg-Binary aus
  `nativeLibraryDir` per `ProcessBuilder` und parsed Fortschritt aus stderr.
* `scripts/build-ffmpeg.sh` – Cross-Compile von LAME + libogg + libvorbis +
  libopus + FFmpeg mit dem Android NDK; das fertige Binary wird als
  `libffmpeg.so` in `app/src/main/jniLibs/<abi>/` abgelegt (Naming-Trick:
  Android extrahiert `lib*.so` ausführbar in den native-lib-Pfad, ProcessBuilder
  kann es von dort starten – auch auf API 29+).
* SAF-URIs werden über `/proc/self/fd/N` an FFmpeg gereicht; deshalb keine
  Storage-Permissions zur Laufzeit.

## Lokal bauen

```bash
ANDROID_NDK_HOME=$ANDROID_NDK_HOME ./scripts/build-ffmpeg.sh arm64-v8a
mkdir -p app/src/main/jniLibs/arm64-v8a
cp build/arm64-v8a/libffmpeg.so app/src/main/jniLibs/arm64-v8a/
gradle :app:assembleDebug
```

Benötigt JDK 17, Android SDK (Plattform 34, Build-Tools 34.0.0) und das NDK
(r25+).

## Lizenzen der gebündelten Bibliotheken

* FFmpeg – LGPL 2.1+
* LAME – LGPL 2.0+
* libogg / libvorbis – BSD-3-Clause
* Opus – BSD-3-Clause

Es ist **kein** GPL-/nonfree-Code eingebunden, daher kann die APK auch
weitergegeben werden, solange die jeweiligen Lizenz-Hinweise mitgeliefert
werden.
