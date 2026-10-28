# ArtCraft Mobile (working name)

An offline Android app to edit **DOCX** documents, **XLSX** spreadsheets, **PPTX**
presentations, **photos** (JPG, PNG, WebP) and **videos** (MP4), and to view **PDF** files.
Files are saved in the format they were opened in, and content the app does not edit is never
destroyed.

> The name is a working name. "ArtCraft" is a trademark of the ArtCraft Team and is not covered by
> the licence of their open-source project; rename the app (or obtain permission) before
> publishing. See [docs/DECISIONS.md](docs/DECISIONS.md#general) (D-013).

- Kotlin, Jetpack Compose, Material 3 with dynamic colour; minSdk 26, targetSdk 36.
- Single activity, MVVM, Navigation Compose, Room (app index), coroutines.
- Offline-first: no accounts, no analytics, no `INTERNET` permission.

## How saving works

The core of the app is a lossless round-trip engine for Open Packaging Conventions files
(`core/xml`, `core/opc`):

- Every ZIP entry is kept as a part. Parts the app does not edit are written back with
  byte-identical content, in their original order.
- XML parts the app edits are parsed into a lossless tree that keeps every attribute, namespace
  declaration, unknown element (`mc:AlternateContent`, `extLst`, custom namespaces), comment and
  whitespace. An unmodified tree serialises to the exact input bytes.
- Unsupported content (charts it cannot edit, SmartArt, OLE objects, equations, form fields...)
  is shown as a locked placeholder with an explanation and saved unchanged.
- Every save goes to a temporary file, is re-opened with the format reader, and only then replaces
  the working copy atomically. The working copy is then written to your file and read back to
  confirm. A crash or a failed save never corrupts the original.
- Autosave runs continuously (1.5 s after the last change and when the app goes to the background).
  Undo/redo is unlimited within a session.

## What you can edit

| App | Editable | Read-only (shown, kept) | Preserved (kept, not shown) |
|---|---|---|---|
| **Documents** (DOCX) | Text, bold/italic/underline/strike, size, colour, highlight, font; headings 1-6 with the document's own styles; bulleted, numbered and nested lists; alignment, indentation, line and paragraph spacing; tables (insert, rows/columns, cell text); images (insert, resize); headers and footers; links; page breaks; page size, margins, orientation; find and replace; word count; PDF export | Tables with merged cells (structure), paragraphs with tracked changes or fields (text), charts, SmartArt, shapes, text boxes, equations, OLE objects, footnote references | Comments, bookmarks, footnote/endnote text, custom XML, macros, theme, settings, fonts |
| **Sheets** (XLSX, CSV) | Values, text, dates, booleans; formulas SUM, AVERAGE, MIN, MAX, COUNT, IF, ROUND, VLOOKUP, CONCAT and ~25 more; number formats (currency, percent, date...); sheets add/rename/delete/move; column width, row height, freeze panes; bold, italic, fill, borders, alignment, wrap; sort; value filter; new bar/line/pie charts; insert/delete rows and columns (with conditions); CSV import/export | Formulas with other functions (cached value shown), existing charts (drawn), merged cells, conditional formatting (listed), tables, pictures, hyperlinks | Pivot tables, comments, data validation, sparklines, slicers, macros, external links |
| **Slides** (PPTX) | Text boxes (text, font, size, bold, italic, underline, colour, bullets, alignment, level); insert, delete, duplicate, reorder, hide slides; choose and change layouts; insert images, rectangles, ellipses, lines, arrows; move, resize, rotate, fill/outline colour; speaker notes; full-screen presentation; PDF export | Tables, charts, SmartArt, media, OLE, groups' structure | Transitions, animations, comments, sections, custom shows, macros |
| **Photo** (JPG, PNG, WebP) | Crop (with aspect presets), rotate, flip, straighten; exposure, contrast, highlights, shadows, saturation, warmth, sharpness; 9 filter presets; before/after; export JPG/PNG/WebP with quality and size; EXIF orientation applied, metadata optional (location opt-in) | | |
| **Video** (MP4) | Trim, split, delete clips, reorder, join clips; clip volume and mute; background music with volume and loop; text overlays (text, size, colour, position, timing, bold); export 1080p, 720p, vertical 9:16 | | Source clips are never modified |
| **PDF** | | View pages | |

Everything element by element: [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md). Each file also has
a **Compatibility** screen in the app.

### Not supported in this version

- Editing: tracked changes, comments, footnote text, equations, SmartArt, charts in DOCX/PPTX,
  table merges, conditional formatting rules, pivot tables, slide transitions and animations.
- Formulas outside the supported list are not calculated by the app (the cached value is kept and
  the file is marked for recalculation on load).
- Photo layers and masks; video colour grading, keyframes and effects (out of scope by design).
- PDF editing; password-protected PDFs.
- Fonts are not bundled: documents are drawn with the device's sans-serif, serif and monospace
  families.

## Project layout

| Module | Contents |
|---|---|
| `core/common` | Undo manager, snapshot history, crash-safe `AtomicFile` |
| `core/xml` | Lossless XML parser/serialiser with undoable mutations |
| `core/opc` | OPC package model: parts, content types, relationships, verified saves, snapshots |
| `format/docx`, `format/xlsx`, `format/pptx` | Format layers (read model, edits, compatibility report) |
| `format/photo`, `format/video` | Photo adjustment pipeline and geometry; video timeline and export plan |
| `testing/corpus` | Corpus discovery, part comparison, LibreOffice second-reader harness |
| `app` | Android app (Compose UI, Room, editors, PDF export, Media3) |
| `test/corpus` | Round-trip corpus (Word, Excel, PowerPoint, LibreOffice, python-docx/openpyxl/python-pptx files) |
| `docs` | [COMPATIBILITY](docs/COMPATIBILITY.md), [DECISIONS](docs/DECISIONS.md), [UX_FLOWS](docs/UX_FLOWS.md) |

The format layers are plain Kotlin/JVM: no Android dependency, unit-tested on any JVM.

## Building

Requirements: JDK 17 or newer, Android SDK (API 36) for the app. LibreOffice is optional for the
second-reader tests.

```bash
# Everything (needs an Android SDK: ANDROID_HOME or local.properties)
./gradlew test :app:assembleDebug

# Round-trip engine and format layers only (no Android SDK needed)
./gradlew roundTripTests :format:photo:test :format:video:test

# Require LibreOffice for the edit tests (as CI does)
SUITE_SECOND_READER=required ./gradlew :format:docx:test

# Screenshot tests (images in app/build/outputs/roborazzi)
./gradlew :app:recordRoborazziDebug
```

Without an Android SDK, `settings.gradle.kts` builds only the JVM modules and says so.

### Continuous integration

`.github/workflows/ci.yml` runs on every push and pull request:

- **Round-trip and format tests** with LibreOffice installed as the second reader.
- **Android app**: `assembleDebug`, app unit tests and Roborazzi screenshots. The debug APK is
  uploaded as the `app-debug-apk` artifact, screenshots as `screenshots`.
- A **signed release APK** is built when these repository secrets exist:
  `SUITE_KEYSTORE_BASE64` (base64 of the keystore), `SUITE_KEYSTORE_PASSWORD`, `SUITE_KEY_ALIAS`,
  `SUITE_KEY_PASSWORD`.

## Installing the APK

1. Open the repository's **Actions** tab, select the latest successful **CI** run and download
   the **app-debug-apk** artifact (a ZIP containing `app-debug.apk`).
2. Copy `app-debug.apk` to the phone and open it, allowing installation from that source when
   asked; or install from a computer with `adb install -r app-debug.apk`.

The debug build is signed with the debug key and can be installed next to nothing else; uninstall
it before installing a release build signed with another key.

## Licences

This project's own code is in this repository; choose a licence before publishing (none is
declared yet).

Third-party components used by the app (all Apache License 2.0): AndroidX (Core, Activity,
Lifecycle, Navigation, Room, ExifInterface, Compose, Material 3, Media3), Kotlin standard library
and kotlinx.coroutines, Guava (through Media3). Test-only: JUnit (EPL 1.0), Robolectric (MIT),
Roborazzi (Apache 2.0).

Test data: files from Apache POI's test data (Apache License 2.0, see
[test/corpus/README.md](test/corpus/README.md) and `test/corpus/NOTICE-apache-poi.txt`); the
remaining corpus files were generated for this project.

Reference project: the ArtCraft desktop app ([github.com/storytold/artcraft](https://github.com/storytold/artcraft))
is dual-licensed **MIT OR Apache-2.0**; its NOTICE states that the ArtCraft name, wordmark and
logos are trademarks not covered by that licence, and lists third-party material under zlib, MIT,
CC BY 4.0 and SIL OFL 1.1. It is an AI image and video creation tool (Rust, Tauri, TypeScript); it
was reviewed for ideas only and **no code, assets or visuals were copied**. Other repositories of
the storytold organisation could not be listed from the build environment and were not used.
Apache POI was evaluated as a library and not used (see D-001); only its test files are included.
